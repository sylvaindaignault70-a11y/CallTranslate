package com.calltranslate

import android.content.Context
import android.media.MediaRecorder
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import org.json.JSONArray
import java.io.File
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
            "fr" to Locale.FRENCH, "en" to Locale.US, "es" to Locale("es","ES"),
            "de" to Locale.GERMAN, "pt" to Locale("pt","BR"), "it" to Locale.ITALIAN,
            "zh" to Locale.CHINESE, "ja" to Locale.JAPANESE, "ko" to Locale.KOREAN,
            "ar" to Locale("ar"), "ru" to Locale("ru")
        )
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

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var listening = false
    private var modeIsMe = true
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val log = mutableListOf<String>()

    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var isRecording = false

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_traduction, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        spinMoi   = v.findViewById(R.id.spinTradMoi)
        spinOther = v.findViewById(R.id.spinTradOther)
        btnMoi    = v.findViewById(R.id.btnMoi)
        btnAutre  = v.findViewById(R.id.btnAutre)
        btnStop   = v.findViewById(R.id.btnStop)
        btnRec    = v.findViewById(R.id.btnRec)
        btnSave   = v.findViewById(R.id.btnSaveText)
        tvStatus  = v.findViewById(R.id.tvTradStatus)
        tvOriginal = v.findViewById(R.id.tvOriginal)
        tvResult  = v.findViewById(R.id.tvTradResult)

        val labels = LANGS.map { it.second }
        spinMoi.adapter   = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)
        spinOther.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, labels)

        val prefs = requireContext().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        spinMoi.setSelection(LANGS.indexOfFirst { it.first == prefs.getString("tradLangMoi","fr") }.coerceAtLeast(0))
        spinOther.setSelection(LANGS.indexOfFirst { it.first == prefs.getString("tradLangOther","en") }.coerceAtLeast(0))

        tts = TextToSpeech(requireContext()) {}

        btnMoi.setOnClickListener    { startListen(true) }
        btnAutre.setOnClickListener  { startListen(false) }
        btnStop.setOnClickListener   { stopAll() }
        btnRec.setOnClickListener    { toggleRec() }
        btnSave.setOnClickListener   { saveText() }
    }

    private fun langMoi()   = LANGS[spinMoi.selectedItemPosition].first
    private fun langOther() = LANGS[spinOther.selectedItemPosition].first

    private fun startListen(isMe: Boolean) {
        stopAll()
        modeIsMe = isMe
        val srcLang = if (isMe) langMoi() else langOther()
        val srLocale = SR_LOCALE[srcLang] ?: "fr-FR"

        recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle?) {
                val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                requireActivity().runOnUiThread { tvOriginal.text = text }
                val from = if (isMe) langMoi() else langOther()
                val to   = if (isMe) langOther() else langMoi()
                scope.launch {
                    val trad = translate(text, from, to)
                    requireActivity().runOnUiThread {
                        tvResult.text = trad
                        tvStatus.text = "✓"
                        speak(trad, to)
                        log.add("[${timestamp()}] ${if(isMe) "MOI" else "AUTRE"}: $text → $trad")
                    }
                }
                if (listening) startListen(isMe)
            }
            override fun onError(e: Int) {
                if (listening) Handler(Looper.getMainLooper()).postDelayed({ startListen(isMe) }, 800)
            }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, srLocale)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        listening = true
        recognizer?.startListening(intent)
        tvStatus.text = if (isMe) "🎤 MOI actif..." else "👂 L'AUTRE actif..."
        btnMoi.alpha   = if (isMe) 1f else 0.5f
        btnAutre.alpha = if (isMe) 0.5f else 1f
    }

    private fun stopAll() {
        listening = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        btnMoi.alpha   = 1f
        btnAutre.alpha = 1f
        tvStatus.text  = "Arrêté"
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
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$from&tl=$to&dt=t&q=$q"
            val raw = URL(url).readText()
            JSONArray(raw).getJSONArray(0).getJSONArray(0).getString(0)
        } catch (e: Exception) { text }
    }

    private fun toggleRec() {
        if (isRecording) {
            recorder?.stop(); recorder?.release(); recorder = null
            isRecording = false
            btnRec.text = "⏺ Rec"
            tvStatus.text = "Enregistrement sauvegardé"
        } else {
            val ctx = requireContext()
            val dir = File(ctx.getExternalFilesDir(null), "Message").also { it.mkdirs() }
            recFile = File(dir, "Rec_${timestamp()}.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(recFile!!.absolutePath)
                prepare(); start()
            }
            isRecording = true
            btnRec.text = "⏹ Stop Rec"
        }
    }

    private fun saveText() {
        if (log.isEmpty()) { tvStatus.text = "Rien à sauvegarder"; return }
        val ctx = requireContext()
        val dir = File(ctx.getExternalFilesDir(null), "Message").also { it.mkdirs() }
        val f = File(dir, "Conv_${timestamp()}.txt")
        f.writeText(log.joinToString("\n\n"))
        tvStatus.text = "Sauvegardé: ${f.name}"
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

    override fun onDestroyView() {
        super.onDestroyView()
        stopAll()
        tts?.shutdown()
        scope.cancel()
        if (isRecording) { recorder?.stop(); recorder?.release() }
    }
}
