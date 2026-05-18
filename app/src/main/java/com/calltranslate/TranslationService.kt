package com.calltranslate

import android.app.*
import android.content.Intent
import android.media.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.*

class TranslationService : Service() {

    companion object {
        @Volatile var isRunning  = false
        var onOriginal:  ((String) -> Unit)? = null
        var onTranslated:((String) -> Unit)? = null
        var onPartial:   ((String) -> Unit)? = null
        var onStatus:    ((String) -> Unit)? = null
        var onRms:       ((Float)  -> Unit)? = null
        var onCallEnded: (() -> Unit)?       = null
        var onMoiSaid:   ((String) -> Unit)? = null
        var onMoiTrad:   ((String) -> Unit)? = null
        var onAutreSaid: ((String) -> Unit)? = null
        var onAutreTrad: ((String) -> Unit)? = null
        @Volatile var forceDirection: Boolean? = null

        private const val NOTIF_ID      = 10
        private const val CHANNEL       = "call_trad"
        const val ACTION_STOP           = "com.calltranslate.STOP_TRAD"
        private const val SAMPLE_RATE   = 16000
        private const val CHUNK_SECONDS = 4
        private const val SILENCE_RMS   = 500.0

        private val TTS_LOC = mapOf(
            "fr" to Locale.FRENCH, "en" to Locale.US, "es" to Locale("es","ES"),
            "de" to Locale.GERMAN, "pt" to Locale("pt","BR"), "it" to Locale.ITALIAN)
        private val WHISPER_LANG = mapOf(
            "french" to "fr", "english" to "en", "spanish" to "es",
            "german" to "de", "portuguese" to "pt", "italian" to "it")
    }

    private var langMoi   = "fr"
    private var langOther = "auto"
    private var wasOffhook = false
    private var tts: TextToSpeech? = null
    @Volatile private var ttsPlaying  = false
    @Volatile private var isCapturing = false
    private var audioRecord: AudioRecord? = null
    private val mainH = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, number: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> { wasOffhook = true; setSpeaker(true) }
                TelephonyManager.CALL_STATE_IDLE -> {
                    setSpeaker(false)
                    if (wasOffhook) { wasOffhook = false; stopCapture(); mainH.post { onCallEnded?.invoke() }; stopSelf() }
                }
            }
        }
    }

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        langMoi   = intent?.getStringExtra("langMoi")   ?: "fr"
        langOther = intent?.getStringExtra("langOther") ?: "auto"
        try { startForeground(NOTIF_ID, buildNotif()) } catch (e: Exception) { Log.e("TS","notif: ${e.message}") }
        tts = TextToSpeech(this) {}
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?)  { ttsPlaying = false }
            @Deprecated("Deprecated in Java")
            override fun onError(u: String?) { ttsPlaying = false }
        })
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        tm.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        if (tm.callState == TelephonyManager.CALL_STATE_OFFHOOK) setSpeaker(true)
        isRunning = true
        mainH.postDelayed({ startCapture() }, 1500)
        return START_STICKY
    }

    private fun startCapture() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(SAMPLE_RATE * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            mainH.post { onStatus?.invoke("⚠ VOICE_COMM bloqué → MIC") }
            audioRecord?.release()
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf)
        }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            mainH.post { onStatus?.invoke("❌ Micro inaccessible") }
            audioRecord?.release(); audioRecord = null; return
        }

        audioRecord?.startRecording()
        isCapturing = true
        mainH.post { onStatus?.invoke("🎤 Capture audio...") }

        scope.launch {
            val chunkTarget = SAMPLE_RATE * 2 * CHUNK_SECONDS
            val readBuf = ByteArray(minBuf)
            val accumulated = mutableListOf<ByteArray>()
            var accSize = 0

            while (isCapturing && isRunning) {
                if (ttsPlaying) {
                    accumulated.clear(); accSize = 0
                    delay(100); continue
                }
                val n = audioRecord?.read(readBuf, 0, readBuf.size) ?: -1
                if (n <= 0) { delay(20); continue }

                val chunk = readBuf.copyOf(n)
                accumulated.add(chunk)
                accSize += n

                val rms = rmsOf(chunk).toFloat()
                mainH.post { onRms?.invoke(rms / 3276.8f) }

                if (accSize >= chunkTarget) {
                    val pcm = accumulated.flatMap { it.toList() }.toByteArray()
                    accumulated.clear(); accSize = 0
                    val pcmRms = rmsOf(pcm)
                    if (pcmRms >= SILENCE_RMS) {
                        transcribeWhisper(pcm)
                    } else {
                        mainH.post { onStatus?.invoke("🔇 Silence") }
                    }
                }
            }
        }
    }

    private fun rmsOf(pcm: ByteArray): Double {
        var sum = 0.0
        var i = 0
        while (i < pcm.size - 1) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s * s; i += 2
        }
        return if (pcm.size >= 2) Math.sqrt(sum / (pcm.size / 2)) else 0.0
    }

    private suspend fun transcribeWhisper(pcm: ByteArray) {
        try {
            val apiKey = SettingsFragment.getApiKey(this)
            if (apiKey.isBlank()) {
                mainH.post { onStatus?.invoke("⚠ Clef OpenAI manquante → onglet Réglages") }
                return
            }
            mainH.post { onStatus?.invoke("☁ Whisper...") }
            val wav = pcmToWav(pcm)
            val boundary = "Bound${System.currentTimeMillis()}"

            val conn = URL("https://api.openai.com/v1/audio/transcriptions")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout   = 15000

            conn.outputStream.use { out ->
                fun w(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                w("--$boundary\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-1\r\n")
                w("--$boundary\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\nverbose_json\r\n")
                w("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
                out.write(wav)
                w("\r\n--$boundary--\r\n")
            }

            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
                       else conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: "HTTP $code"

            if (code != 200) {
                mainH.post { onStatus?.invoke("⚠ Whisper $code: ${body.take(80)}") }
                return
            }

            val json = JSONObject(body)
            val text = json.getString("text").trim()
            val whisperLang = json.optString("language", "").lowercase()
            val detectedCode = WHISPER_LANG[whisperLang] ?: ""

            if (text.isBlank()) {
                mainH.post { onStatus?.invoke("🔇 Whisper: rien") }
                return
            }
            mainH.post { onStatus?.invoke("✓ [$whisperLang] $text") }

            // Auto-direction from Whisper detected language
            if (forceDirection == null && detectedCode.isNotEmpty()) {
                forceDirection = (detectedCode == langMoi)
            }
            mainH.post { onOriginal?.invoke(text) }
            translateAndSpeak(text)

        } catch (e: Exception) {
            Log.e("TS", "whisper: ${e.message}")
            mainH.post { onStatus?.invoke("⚠ ${e.message?.take(60)}") }
        }
    }

    private suspend fun translateAndSpeak(text: String) {
        try {
            val q = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$langMoi&dt=t&q=$q"
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val json = JSONArray(raw)
            val translatedToMoi = json.getJSONArray(0).getJSONArray(0).getString(0)
            val detectedLang = try {
                if (json.length() > 2 && !json.isNull(2)) json.getString(2) else ""
            } catch (e: Exception) { "" }

            val dir = forceDirection
            forceDirection = null
            val isMoiSpeaking = when (dir) {
                true  -> true
                false -> false
                null  -> when {
                    detectedLang.isNotEmpty() -> detectedLang == langMoi
                    else -> translatedToMoi.trim().equals(text.trim(), ignoreCase = true)
                }
            }
            mainH.post { onStatus?.invoke("🔍 détecté:$detectedLang moi=$langMoi") }

            if (isMoiSpeaking && langOther != "auto") {
                mainH.post { onMoiSaid?.invoke(text); onOriginal?.invoke(text) }
                val q2 = URLEncoder.encode(text, "UTF-8")
                val url2 = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$langMoi&tl=$langOther&dt=t&q=$q2"
                val conn2 = URL(url2).openConnection() as java.net.HttpURLConnection
                conn2.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn2.connectTimeout = 5000; conn2.readTimeout = 5000
                val raw2 = conn2.inputStream.bufferedReader(Charsets.UTF_8).readText()
                val tradToOther = JSONArray(raw2).getJSONArray(0).getJSONArray(0).getString(0)
                if (tradToOther.isNotBlank()) mainH.post {
                    speak(tradToOther, langOther)
                    onMoiTrad?.invoke(tradToOther); onTranslated?.invoke("→ $tradToOther")
                }
            } else if (translatedToMoi.isNotBlank() && !translatedToMoi.trim().equals(text.trim(), ignoreCase = true)) {
                mainH.post { onAutreSaid?.invoke(text); onOriginal?.invoke(text) }
                mainH.post {
                    speak(translatedToMoi, langMoi)
                    onAutreTrad?.invoke(translatedToMoi); onTranslated?.invoke(translatedToMoi)
                }
            } else {
                mainH.post { onStatus?.invoke("⚠ ignoré (même langue ou boucle)") }
            }
        } catch (e: Exception) { Log.e("TS", e.message ?: "") }
    }

    private fun speak(text: String, lang: String = langMoi) {
        ttsPlaying = true
        val locale = TTS_LOC[lang] ?: Locale.getDefault()
        tts?.language = locale
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ts${System.currentTimeMillis()}")
    }

    private fun stopCapture() {
        isCapturing = false
        try { audioRecord?.stop() } catch (e: Exception) {}
        audioRecord?.release()
        audioRecord = null
    }

    private fun setSpeaker(on: Boolean) {
        (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = on
    }

    private fun pcmToWav(pcm: ByteArray): ByteArray {
        val ch = 1; val bits = 16
        val byteRate = SAMPLE_RATE * ch * bits / 8
        val out = java.io.ByteArrayOutputStream()
        fun Int.le4() = byteArrayOf(toByte(), shr(8).toByte(), shr(16).toByte(), shr(24).toByte())
        fun Short.le2() = byteArrayOf(toByte(), toInt().shr(8).toByte())
        out.write("RIFF".toByteArray()); out.write((36 + pcm.size).le4())
        out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
        out.write(16.le4()); out.write(1.toShort().le2()); out.write(ch.toShort().le2())
        out.write(SAMPLE_RATE.le4()); out.write(byteRate.le4())
        out.write((ch * bits / 8).toShort().le2()); out.write(bits.toShort().le2())
        out.write("data".toByteArray()); out.write(pcm.size.le4()); out.write(pcm)
        return out.toByteArray()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Traduction Appel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPI = PendingIntent.getService(this, 1,
            Intent(this, TranslationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("🌐 Traduction Appel ACTIVE")
            .setContentText("MOI: $langMoi | AUTRE: $langOther — Whisper STT")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "⏹ Arrêter", stopPI)
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        stopCapture()
        tts?.shutdown()
        @Suppress("DEPRECATION")
        (getSystemService(TELEPHONY_SERVICE) as TelephonyManager)
            .listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null
}
