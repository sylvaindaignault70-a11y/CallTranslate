package com.calltranslate

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentValues
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
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
    private lateinit var pbAppelRms: ProgressBar
    private lateinit var scrollAppelDebug: android.widget.ScrollView
    private val appelDebugLog = StringBuilder()

    private lateinit var tvContactNum: TextView
    private lateinit var etNumero: EditText
    private var selectedTel: String? = null

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri -> uri?.let { loadContact(it) } }

    private val requestCallPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) signaler() }

    private val requestEndCallPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) raccrocher() }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val pcmChunks = mutableListOf<ByteArray>()
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

        tvContactNum = v.findViewById(R.id.tvContactNum)
        etNumero     = v.findViewById(R.id.etNumero)

        v.findViewById<Button>(R.id.btnContacts).setOnClickListener { pickContact.launch(null) }
        v.findViewById<Button>(R.id.btnVider).setOnClickListener { vider() }
        v.findViewById<Button>(R.id.btnSignaler).setOnClickListener { signaler() }

        tvAppelDebugLog = v.findViewById(R.id.tvAppelDebugLog)
        tvAppelRms = v.findViewById(R.id.tvAppelRms)
        pbAppelRms = v.findViewById(R.id.pbAppelRms)
        scrollAppelDebug = v.findViewById(R.id.scrollAppelDebug)
        v.findViewById<Button>(R.id.btnAppelDebugClear).setOnClickListener {
            appelDebugLog.clear(); tvAppelDebugLog.text = ""
        }

        TranslationService.onRms = { rms ->
            if (isAdded) activity?.runOnUiThread {
                tvAppelRms.text = "%.1f".format(rms)
                pbAppelRms.progress = (rms.coerceIn(0f, 32767f) / 32767f * 100).toInt()
            }
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
            saveWavDirect()
        } else {
            startAudioRecording()
            btnCallRec.text = "⏹ Stop Rec"
        }
    }

    private fun startAudioRecording() {
        if (isRecording) return
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) { dbg("⚠ Rec: permission RECORD_AUDIO manquante"); return }
        val bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            dbg("⚠ Rec: AudioRecord non initialisé (source bloquée?) — essai MIC")
            audioRecord?.release()
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            dbg("⚠ Rec: MIC aussi bloqué — abandon"); audioRecord?.release(); audioRecord = null; return
        }
        pcmChunks.clear()
        audioRecord?.startRecording()
        isRecording = true
        dbg("🔴 Rec démarré (${SAMPLE_RATE}Hz)")
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
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(pcmToWav(pcm)) }
                if (isAdded) requireActivity().runOnUiThread { tvStatus.text = "✓ Audio sauvegardé" }
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread { tvStatus.text = "⚠ Erreur audio" }
            }
        }
    }

    private fun saveWavDirect() {
        val count = synchronized(pcmChunks) { pcmChunks.size }
        dbg("💾 Rec stop: $count chunks")
        if (count == 0) { tvStatus.text = "⚠ Rien enregistré (MIC bloqué?)"; return }
        val ts = timestamp()
        scope.launch {
            try {
                val pcm = synchronized(pcmChunks) { pcmChunks.flatMap { it.toList() }.toByteArray() }
                dbg("💾 PCM: ${pcm.size} bytes → WAV")
                val wav = pcmToWav(pcm)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "Rec_$ts.wav")
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/Application/Ecouteur/Message")
                }
                val uri = requireContext().contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { it.write(wav) }
                    if (isAdded) requireActivity().runOnUiThread {
                        tvStatus.text = "✓ Rec_$ts.wav → Downloads/Application/Ecouteur/Message"
                    }
                    dbg("✓ WAV sauvegardé: Rec_$ts.wav")
                } else {
                    if (isAdded) requireActivity().runOnUiThread { tvStatus.text = "⚠ Erreur MediaStore" }
                    dbg("⚠ MediaStore insert = null")
                }
            } catch (e: Exception) {
                if (isAdded) requireActivity().runOnUiThread { tvStatus.text = "⚠ ${e.message}" }
                dbg("⚠ saveWav: ${e.message}")
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

    private fun loadContact(uri: Uri) {
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )
        val cursor = requireContext().contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(uri.lastPathSegment), null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                selectedTel = it.getString(0)
                val name = it.getString(1)
                tvContactNum.text = "$name — $selectedTel"
            }
        }
    }

    private fun vider() {
        selectedTel = null
        tvContactNum.text = "—"
        etNumero.text.clear()
    }

    private fun signaler() {
        val num = selectedTel ?: etNumero.text.toString().trim()
        if (num.isBlank()) { tvStatus.text = "⚠ Aucun numéro"; return }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED) {
            requestCallPerm.launch(Manifest.permission.CALL_PHONE)
            return
        }
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
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
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            if (requireContext().checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS)
                != PackageManager.PERMISSION_GRANTED) {
                requestEndCallPerm.launch(android.Manifest.permission.ANSWER_PHONE_CALLS)
                return
            }
            try {
                val tm = requireContext().getSystemService(android.telecom.TelecomManager::class.java)
                tm?.endCall()
                dbg("📵 endCall() envoyé")
            } catch (e: Exception) { dbg("⚠ Raccrocher: ${e.message}") }
        }
    }

    private fun timestamp() = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())

    override fun onDestroyView() {
        super.onDestroyView()
        TranslationService.onStatus     = null
        TranslationService.onPartial    = null
        TranslationService.onOriginal   = null
        TranslationService.onTranslated = null
        TranslationService.onRms        = null
        stopAudioRecording()
        scope.cancel()
    }
}
