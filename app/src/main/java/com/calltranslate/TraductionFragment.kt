package com.calltranslate

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.speech.*
import android.util.Log
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

class TraductionFragment : Fragment() {

    companion object {
        val LANGS = listOf(
            "fr" to "🇫🇷 Français", "en" to "🇬🇧 Anglais",
            "es" to "🇪🇸 Espagnol", "de" to "🇩🇪 Allemand",
            "pt" to "🇵🇹 Portugais", "it" to "🇮🇹 Italien",
            "zh" to "🇨🇳 Chinois", "ja" to "🇯🇵 Japonais",
            "ko" to "🇰🇷 Coréen", "ar" to "🇸🇦 Arabe",
            "ru" to "🇷🇺 Russe"
        )
        val SR_LOCALE = mapOf(
            "fr" to "fr-FR", "en" to "en-US", "es" to "es-ES",
            "de" to "de-DE", "pt" to "pt-BR", "it" to "it-IT",
            "zh" to "zh-CN", "ja" to "ja-JP", "ko" to "ko-KR",
            "ar" to "ar-SA", "ru" to "ru-RU"
        )
        val TTS_LOCALE = mapOf(
            "fr" to Locale.FRENCH, "en" to Locale.US, "es" to Locale("es", "ES"),
            "de" to Locale.GERMAN, "pt" to Locale("pt", "BR"), "it" to Locale.ITALIAN,
            "zh" to Locale.CHINESE, "ja" to Locale.JAPANESE, "ko" to Locale.KOREAN,
            "ar" to Locale("ar"), "ru" to Locale("ru")
        )
        private const val SAMPLE_RATE = 44100
    }

    private lateinit var spinMoi: Spinner
    private lateinit var spinOther: Spinner
    private lateinit var btnMoi: Button
    private lateinit var btnAutre: Button
    private lateinit var btnStop: Button
    private lateinit var btnRec: Button
    private lateinit var btnSave: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvOriginal: TextView
    private lateinit var tvResult: TextView
    private lateinit var tvDebugLog: TextView
    private lateinit var tvDebugRms: TextView
    private lateinit var scrollDebug: android.widget.ScrollView
    private val debugLog = StringBuilder()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val log = StringBuilder()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val pcmChunks = mutableListOf<ByteArray>()
    private var recordingStartMs = 0L

    private val saveWavLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri -> uri?.let { saveWav(it) } }

    private val saveTxtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { saveTxt(it) } }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_traduction, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        spinMoi    = v.findViewById(R.id.spinTradMoi)
        spinOther  = v.findViewById(R.id.spinTradOther)
        btnMoi     = v.findViewById(R.id.btnMoi)
        btnAutre   = v.findViewById(R.id.btnAutre)
        btnStop    = v.findViewById(R.id.btnStop)
        btnRec     = v.findViewById(R.id.btnRec)
        btnSave    = v.findViewById(R.id.btnSaveText)
        tvStatus   = v.findViewById(R.id.tvTradStatus)
        tvOriginal = v.findViewById(R.id.tvOriginal)
        tvResult   = v.findViewById(R.id.tvTradResult)
        tvDebugLog = v.findViewById(R.id.tvDebugLog)
        tvDebugRms = v.findViewById(R.id.tvDebugRms)
        scrollDebug = v.findViewById(R.id.scrollDebug)

        v.findViewById<Button>(R.id.btnDebugClear).setOnClickListener {
            debugLog.clear(); tvDebugLog.text = ""
        }
        v.findViewById<Button>(R.id.btnRaccrocher).setOnClickListener {
            try {
                val tm = requireContext().getSystemService(android.telecom.TelecomManager::class.java)
                if (android.os.Build.VERSION.SDK_INT >= 28 &&
                    requireContext().checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    tm?.endCall()
                } else {
                    requireActivity().sendBroadcast(android.content.Intent(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                }
            } catch (e: Exception) { dbg("Raccrocher err: ${e.message}") }
        }

        val labels = LANGS.map { it.second }
        spinMoi.adapter   = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinOther.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)

        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        spinMoi.setSelection(LANGS.indexOfFirst { it.first == prefs.getString("tradLangMoi", "fr") }.coerceAtLeast(0))
        spinOther.setSelection(LANGS.indexOfFirst { it.first == prefs.getString("tradLangOther", "en") }.coerceAtLeast(0))

        tts = TextToSpeech(requireContext()) {}

        dbg("🐛 DEBUG prêt — appuie MOI ou L'AUTRE")

        btnMoi.setOnClickListener   { startListen(true) }
        btnAutre.setOnClickListener { startListen(false) }
        btnStop.setOnClickListener  { stopAndSave() }
        btnRec.setOnClickListener   { toggleRec() }
        btnSave.setOnClickListener  { showSaveDialog(timestamp()) }
    }

    private fun langMoi()   = LANGS[spinMoi.selectedItemPosition].first
    private fun langOther() = LANGS[spinOther.selectedItemPosition].first

    private fun dbg(msg: String) {
        val line = "[${timestamp()}] $msg\n"
        Log.d("CT_SPEECH", msg)
        requireActivity().runOnUiThread {
            debugLog.append(line)
            tvDebugLog.text = debugLog
            scrollDebug.post { scrollDebug.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun startListen(isMe: Boolean) {
        if (!isRecording) startAudioRecording()
        val srcLang = if (isMe) langMoi() else langOther()
        val srLocale = SR_LOCALE[srcLang] ?: "fr-FR"

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                dbg("✅ RESULT ${if (isMe) "MOI" else "AUTRE"}: $text")
                requireActivity().runOnUiThread { tvOriginal.text = text }
                val from = if (isMe) langMoi() else langOther()
                val to   = if (isMe) langOther() else langMoi()
                scope.launch {
                    val trad = translate(text, from, to)
                    requireActivity().runOnUiThread {
                        if (trad.isNotBlank()) {
                            tvResult.text = trad
                            tvStatus.text = "✓"
                            speak(trad, to)
                            log.append("[${timestamp()}] ${if (isMe) "MOI" else "AUTRE"}: $text → $trad\n\n")
                        } else {
                            tvStatus.text = "⚠ Traduction échouée (réseau?)"
                        }
                    }
                }
                if (listening) startListen(isMe)
            }
            override fun onError(e: Int) {
                val msg = when(e) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO(3)-mic bloqué?"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_BUSY(8)"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH(7)"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_TIMEOUT(6)"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_PERMISSION(9)"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK(2)"
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANG_UNSUPPORTED(13)"
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANG_UNAVAIL(14)"
                    else -> "ERROR_$e"
                }
                dbg("❌ $msg locale=$srLocale isMe=$isMe")
                requireActivity().runOnUiThread { tvStatus.text = "⚠ $msg" }
                if (listening) Handler(Looper.getMainLooper()).postDelayed({ if (listening) startListen(isMe) }, 800)
            }
            override fun onReadyForSpeech(p: Bundle?) { dbg("🟢 READY locale=$srLocale isMe=$isMe") }
            override fun onBeginningOfSpeech() { dbg("🔴 SPEECH DETECTED") }
            override fun onRmsChanged(v: Float) {
                requireActivity().runOnUiThread { tvDebugRms.text = "%.1f".format(v) }
            }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { dbg("⏹ END OF SPEECH") }
            override fun onPartialResults(b: Bundle?) {
                val partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                dbg("〜 PARTIAL: $partial")
            }
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, srLocale)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        recognizer?.startListening(intent)
        tvStatus.text  = if (isMe) "🎤 MOI actif..." else "👂 L'AUTRE actif..."
        btnMoi.alpha   = if (isMe) 1f else 0.5f
        btnAutre.alpha = if (isMe) 0.5f else 1f
    }

    private fun stopListening() {
        listening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        btnMoi.alpha   = 1f
        btnAutre.alpha = 1f
    }

    private fun stopAndSave() {
        stopListening()
        stopAudioRecording()
        tvStatus.text = "Arrêté"
        if (log.isEmpty() && pcmChunks.isEmpty()) return
        showSaveDialog(timestamp(), fmtDuration())
    }

    private fun showSaveDialog(ts: String, dur: String = "") {
        val hasText  = log.isNotEmpty()
        val hasAudio = pcmChunks.isNotEmpty()
        if (!hasText && !hasAudio) { tvStatus.text = "Rien à sauvegarder"; return }
        val wavName = if (dur.isNotEmpty()) "Rec_${ts}_${dur}.wav" else "Rec_$ts.wav"
        AlertDialog.Builder(requireContext())
            .setTitle("💾 Sauvegarder")
            .setItems(buildList {
                if (hasText)  add("📄 Texte .txt")
                if (hasAudio) add("🎙 Audio .wav ($dur)")
            }.toTypedArray()) { _, which ->
                val options = buildList {
                    if (hasText)  add("txt")
                    if (hasAudio) add("wav")
                }
                when (options[which]) {
                    "txt" -> saveTxtLauncher.launch("Conv_$ts.txt")
                    "wav" -> saveWavLauncher.launch(wavName)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toggleRec() {
        if (isRecording) {
            val dur = fmtDuration()
            stopAudioRecording()
            btnRec.text = "⏺ Rec"
            tvStatus.text = "Enregistrement arrêté ($dur)"
            if (pcmChunks.isNotEmpty()) {
                val ts = timestamp()
                AlertDialog.Builder(requireContext())
                    .setTitle("💾 Sauvegarder audio")
                    .setMessage("Sauvegarder l'enregistrement en .wav ?")
                    .setPositiveButton("🎙 .wav") { _, _ -> saveWavLauncher.launch("Rec_${ts}_${dur}.wav") }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        } else {
            startAudioRecording()
            btnRec.text = "⏹ Stop Rec"
            tvStatus.text = "🔴 Enregistrement..."
        }
    }

    private fun startAudioRecording() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        pcmChunks.clear()
        recordingStartMs = System.currentTimeMillis()
        audioRecord?.startRecording()
        isRecording = true
        Thread {
            val buf = ByteArray(bufSize)
            while (isRecording) {
                val n = audioRecord?.read(buf, 0, bufSize) ?: 0
                if (n > 0) synchronized(pcmChunks) { pcmChunks.add(buf.copyOf(n)) }
            }
        }.start()
    }

    private fun fmtDuration(): String {
        val s = (System.currentTimeMillis() - recordingStartMs) / 1000
        return if (s >= 60) "${s / 60}m${s % 60}s" else "${s}s"
    }

    private fun stopAudioRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun saveWav(uri: Uri) {
        scope.launch {
            try {
                val pcm = synchronized(pcmChunks) { pcmChunks.flatMap { it.toList() }.toByteArray() }
                val wav = pcmToWav(pcm)
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(wav) }
                requireActivity().runOnUiThread { tvStatus.text = "✓ Audio sauvegardé" }
            } catch (e: Exception) {
                requireActivity().runOnUiThread { tvStatus.text = "⚠ Erreur sauvegarde audio" }
            }
        }
    }

    private fun saveTxt(uri: Uri) {
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use {
                it.write(log.toString().toByteArray(Charsets.UTF_8))
            }
            tvStatus.text = "✓ Texte sauvegardé"
        } catch (e: Exception) {
            tvStatus.text = "⚠ Erreur sauvegarde texte"
        }
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val out = java.io.ByteArrayOutputStream()
        fun Int.le4() = byteArrayOf(toByte(), shr(8).toByte(), shr(16).toByte(), shr(24).toByte())
        fun Short.le2() = byteArrayOf(toByte(), toInt().shr(8).toByte())
        out.write("RIFF".toByteArray())
        out.write((36 + pcm.size).le4())
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(16.le4())
        out.write(1.toShort().le2())
        out.write(channels.toShort().le2())
        out.write(SAMPLE_RATE.le4())
        out.write(byteRate.le4())
        out.write((channels * bitsPerSample / 8).toShort().le2())
        out.write(bitsPerSample.toShort().le2())
        out.write("data".toByteArray())
        out.write(pcm.size.le4())
        out.write(pcm)
        return out.toByteArray()
    }

    private fun speak(text: String, lang: String) {
        val locale = TTS_LOCALE[lang] ?: Locale.getDefault()
        tts?.language = locale
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "t${System.currentTimeMillis()}")
    }

    private suspend fun translate(text: String, from: String, to: String): String {
        return try {
            val q = URLEncoder.encode(text, "UTF-8")
            val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=$q"
            val conn = URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.setRequestProperty("Accept-Charset", "UTF-8")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            JSONArray(raw).getJSONArray(0).getJSONArray(0).getString(0)
        } catch (e: Exception) { "" }
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

    override fun onDestroyView() {
        super.onDestroyView()
        stopListening()
        stopAudioRecording()
        tts?.shutdown()
        scope.cancel()
    }
}
