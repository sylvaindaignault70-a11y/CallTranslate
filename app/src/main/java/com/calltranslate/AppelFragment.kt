package com.calltranslate

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class AppelFragment : Fragment() {

    companion object {
        val LANGS_MOI   = listOf("fr" to "🇫🇷 Français", "en" to "🇬🇧 Anglais")
        val LANGS_OTHER = listOf(
            "auto" to "🔍 Auto", "fr" to "🇫🇷 Français",
            "en" to "🇬🇧 Anglais", "es" to "🇪🇸 Espagnol"
        )
        private const val SAMPLE_RATE = 44100
    }

    private val log = StringBuilder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var spinMoi: Spinner
    private lateinit var spinOther: Spinner
    private lateinit var btnTrad: Button
    private lateinit var btnCallRec: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvCallOriginal: TextView
    private lateinit var tvCallResult: TextView
    private lateinit var tvAppelDebugLog: TextView
    private lateinit var tvAppelRms: TextView
    private lateinit var scrollAppelDebug: android.widget.ScrollView
    private val appelDebugLog = StringBuilder()

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val pcmChunks = mutableListOf<ByteArray>()
    private var pendingBitmap: Bitmap? = null

    private val saveWavLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("audio/wav")
    ) { uri -> uri?.let { saveWav(it) } }

    private val saveTxtLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri ?: return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use {
                it.write(log.toString().toByteArray(Charsets.UTF_8))
            }
            tvStatus.text = "✓ Texte sauvegardé"
        } catch (e: Exception) { tvStatus.text = "⚠ Erreur" }
    }

    private val savePngLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri ->
        uri ?: return@registerForActivityResult
        val bmp = pendingBitmap ?: return@registerForActivityResult
        try {
            requireContext().contentResolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            tvStatus.text = "✓ Capture sauvegardée"
        } catch (e: Exception) { tvStatus.text = "⚠ Erreur capture" }
        pendingBitmap = null
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_appel, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        spinMoi        = v.findViewById(R.id.spinCallMoi)
        spinOther      = v.findViewById(R.id.spinCallOther)
        btnTrad        = v.findViewById(R.id.btnCallTrad)
        btnCallRec     = v.findViewById(R.id.btnCallRec)
        tvStatus       = v.findViewById(R.id.tvCallStatus)
        tvCallOriginal = v.findViewById(R.id.tvCallOriginal)
        tvCallResult   = v.findViewById(R.id.tvCallResult)

        spinMoi.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, LANGS_MOI.map { it.second })
        spinOther.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, LANGS_OTHER.map { it.second })

        val prefs = requireContext().getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
        spinMoi.setSelection(LANGS_MOI.indexOfFirst { it.first == prefs.getString("callLangMoi", "fr") }.coerceAtLeast(0))
        spinOther.setSelection(LANGS_OTHER.indexOfFirst { it.first == prefs.getString("callLangOther", "auto") }.coerceAtLeast(0))

        tvAppelDebugLog = v.findViewById(R.id.tvAppelDebugLog)
        tvAppelRms = v.findViewById(R.id.tvAppelRms)
        scrollAppelDebug = v.findViewById(R.id.scrollAppelDebug)
        v.findViewById<Button>(R.id.btnAppelDebugClear).setOnClickListener {
            appelDebugLog.clear(); tvAppelDebugLog.text = ""
        }

        TranslationService.onRms = { rms ->
            if (isAdded) activity?.runOnUiThread { tvAppelRms.text = "%.1f".format(rms) }
        }

        dbg("🐛 Appel V2 prêt — ouvre avant d'appeler")

        TranslationService.onStatus = { status ->
            dbg("📡 STATUS: $status")
            if (isAdded) activity?.runOnUiThread { tvStatus.text = status }
        }
        TranslationService.onPartial = { partial ->
            dbg("〜 PARTIAL: $partial")
            if (isAdded) activity?.runOnUiThread { tvCallOriginal.text = "... $partial" }
        }
        TranslationService.onOriginal = { text ->
            dbg("🎤 ORIGINAL: $text")
            if (isAdded) activity?.runOnUiThread { tvCallOriginal.text = text }
        }
        TranslationService.onTranslated = { trad ->
            dbg("🌐 TRAD: $trad")
            if (isAdded) activity?.runOnUiThread {
                tvCallResult.text = trad
                log.append("[${timestamp()}] $trad\n")
            }
        }

        btnTrad.setOnClickListener  { toggleTrad() }
        btnCallRec.setOnClickListener { toggleRec() }
        v.findViewById<Button>(R.id.btnCallCapture).setOnClickListener { takeScreenshot() }
        v.findViewById<Button>(R.id.btnCallSave).setOnClickListener   { showSaveDialog() }
        v.findViewById<Button>(R.id.btnRaccrocheur).setOnClickListener { raccrocher() }

        updateTradUI()
    }

    private fun toggleTrad() {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
        val langMoi   = LANGS_MOI[spinMoi.selectedItemPosition].first
        val langOther = LANGS_OTHER[spinOther.selectedItemPosition].first
        prefs.edit().putString("callLangMoi", langMoi).putString("callLangOther", langOther).apply()

        if (TranslationService.isRunning) {
            ctx.stopService(Intent(ctx, TranslationService::class.java))
        } else {
            val intent = Intent(ctx, TranslationService::class.java).apply {
                putExtra("langMoi", langMoi)
                putExtra("langOther", langOther)
            }
            ContextCompat.startForegroundService(ctx, intent)
        }
        updateTradUI()
    }

    private fun updateTradUI() {
        if (TranslationService.isRunning) {
            btnTrad.text = "⏹ Arrêter traduction"
            btnTrad.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFef4444.toInt())
            tvStatus.text = "✅ Actif — mets l'appel sur haut-parleur"
            tvStatus.setTextColor(0xFF10b981.toInt())
        } else {
            btnTrad.text = "▶ Démarrer traduction"
            btnTrad.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF10b981.toInt())
            tvStatus.text = "Arrêté"
            tvStatus.setTextColor(0xFF64748b.toInt())
        }
    }

    private fun toggleRec() {
        if (isRecording) {
            stopAudioRecording()
            btnCallRec.text = "⏺ Rec"
            if (pcmChunks.isNotEmpty()) {
                val ts = timestamp()
                AlertDialog.Builder(requireContext())
                    .setTitle("💾 Sauvegarder audio")
                    .setPositiveButton("🎙 .wav") { _, _ -> saveWavLauncher.launch("Rec_$ts.wav") }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        } else {
            startAudioRecording()
            btnCallRec.text = "⏹ Stop Rec"
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
                requireActivity().runOnUiThread { tvStatus.text = "⚠ Erreur audio" }
            }
        }
    }

    private fun showSaveDialog() {
        val hasText  = log.isNotEmpty()
        val hasAudio = pcmChunks.isNotEmpty()
        if (!hasText && !hasAudio) { tvStatus.text = "Rien à sauvegarder"; return }
        val ts = timestamp()
        AlertDialog.Builder(requireContext())
            .setTitle("💾 Sauvegarder")
            .setItems(buildList {
                if (hasText)  add("📄 Texte .txt")
                if (hasAudio) add("🎙 Audio .wav")
            }.toTypedArray()) { _, which ->
                val opts = buildList {
                    if (hasText)  add("txt")
                    if (hasAudio) add("wav")
                }
                when (opts[which]) {
                    "txt" -> saveTxtLauncher.launch("Conv_$ts.txt")
                    "wav" -> saveWavLauncher.launch("Rec_$ts.wav")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun takeScreenshot() {
        val rootView = requireActivity().window.decorView
        val bmp = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        rootView.draw(canvas)
        pendingBitmap = bmp
        savePngLauncher.launch("Capture_${timestamp()}.png")
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1; val bitsPerSample = 16
        val byteRate = SAMPLE_RATE * channels * bitsPerSample / 8
        val out = java.io.ByteArrayOutputStream()
        fun Int.le4() = byteArrayOf(toByte(), shr(8).toByte(), shr(16).toByte(), shr(24).toByte())
        fun Short.le2() = byteArrayOf(toByte(), toInt().shr(8).toByte())
        out.write("RIFF".toByteArray()); out.write((36 + pcm.size).le4())
        out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
        out.write(16.le4()); out.write(1.toShort().le2()); out.write(channels.toShort().le2())
        out.write(SAMPLE_RATE.le4()); out.write(byteRate.le4())
        out.write((channels * bitsPerSample / 8).toShort().le2()); out.write(bitsPerSample.toShort().le2())
        out.write("data".toByteArray()); out.write(pcm.size.le4()); out.write(pcm)
        return out.toByteArray()
    }

    private fun dbg(msg: String) {
        android.util.Log.d("CT_APPEL", msg)
        if (isAdded) activity?.runOnUiThread {
            appelDebugLog.append("[${timestamp()}] $msg\n")
            tvAppelDebugLog.text = appelDebugLog
            scrollAppelDebug.post { scrollAppelDebug.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }

    private fun raccrocher() {
        try {
            val tm = requireContext().getSystemService(android.telecom.TelecomManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 28 &&
                requireContext().checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                tm?.endCall()
            }
        } catch (e: Exception) { }
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

    override fun onDestroyView() {
        super.onDestroyView()
        TranslationService.onStatus     = null
        TranslationService.onPartial    = null
        TranslationService.onOriginal   = null
        TranslationService.onTranslated = null
        stopAudioRecording()
        scope.cancel()
    }
}
