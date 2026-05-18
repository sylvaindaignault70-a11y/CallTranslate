package com.calltranslate

import android.app.*
import android.content.Intent
import android.media.AudioManager
import android.os.*
import android.speech.*
import android.speech.tts.TextToSpeech
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import java.util.*

class TranslationService : Service() {

    companion object {
        @Volatile var isRunning = false
        var onOriginal:   ((String) -> Unit)? = null
        var onTranslated: ((String) -> Unit)? = null
        var onPartial:    ((String) -> Unit)? = null
        var onStatus:     ((String) -> Unit)? = null
        var onRms:        ((Float) -> Unit)?  = null
        private const val NOTIF_ID  = 10
        private const val CHANNEL   = "call_trad"
        private val SR_LANG = mapOf("fr" to "fr-FR", "en" to "en-US", "es" to "es-ES")
        private val TTS_LOC = mapOf(
            "fr" to Locale.FRENCH, "en" to Locale.US, "es" to Locale("es","ES"))
    }

    private var langMoi   = "fr"
    private var langOther = "auto"
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var callActive    = false
    private var wasOffhook    = false
    private var listening     = false
    private val mainH = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, number: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> { wasOffhook = true; setSpeaker(true) }
                TelephonyManager.CALL_STATE_IDLE    -> {
                    setSpeaker(false)
                    // Only stop if a real call ended (not initial IDLE fired on listener register)
                    if (wasOffhook) { wasOffhook = false; stopListen(); stopSelf() }
                }
            }
        }
    }

    private val recListener = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            listening = false
            val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                mainH.post { onOriginal?.invoke(text); onStatus?.invoke("✓ Capté") }
                scope.launch { translateAndSpeak(text) }
            }
            if (isRunning) mainH.postDelayed(::doListen, 500)
        }
        override fun onError(e: Int) {
            listening = false
            val name = when(e) {
                SpeechRecognizer.ERROR_AUDIO                  -> "AUDIO(3)=micro bloqué"
                SpeechRecognizer.ERROR_CLIENT                 -> "CLIENT(5)"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "NO_PERM(9)"
                SpeechRecognizer.ERROR_NETWORK                -> "NETWORK(2)"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT        -> "NET_TIMEOUT(1)"
                SpeechRecognizer.ERROR_NO_MATCH               -> "NO_MATCH(7)=capté mais pas reconnu"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY        -> "BUSY(8)"
                SpeechRecognizer.ERROR_SERVER                 -> "SERVER(2)"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT         -> "TIMEOUT(6)=aucun son détecté"
                else -> "UNKNOWN($e)"
            }
            mainH.post { onStatus?.invoke("❌ $name — relance...") }
            if (isRunning) mainH.postDelayed(::doListen, 1000)
        }
        override fun onReadyForSpeech(p: Bundle?) { mainH.post { onStatus?.invoke("🟢 SR prêt") } }
        override fun onBeginningOfSpeech() { mainH.post { onStatus?.invoke("🔊 Parole détectée") } }
        override fun onRmsChanged(v: Float) { onRms?.invoke(v) }
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() { mainH.post { onStatus?.invoke("⏹ Fin parole") } }
        override fun onPartialResults(b: Bundle?) {
            val partial = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!partial.isNullOrBlank()) mainH.post { onPartial?.invoke(partial) }
        }
        override fun onEvent(t: Int, b: Bundle?) {}
    }

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        langMoi   = intent?.getStringExtra("langMoi")   ?: "fr"
        langOther = intent?.getStringExtra("langOther") ?: "auto"
        try { startForeground(NOTIF_ID, buildNotif()) } catch (e: Exception) { Log.e("TS","notif: ${e.message}") }
        tts = TextToSpeech(this) {}
        mainH.post {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this)
                recognizer?.setRecognitionListener(recListener)
            } catch (e: Exception) { Log.e("TS","sr: ${e.message}") }
        }
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        @Suppress("DEPRECATION")
        tm.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
        // Start listening immediately — don't wait for PhoneStateListener (deprecated on API 31+)
        callActive = true
        if (tm.callState == TelephonyManager.CALL_STATE_OFFHOOK) setSpeaker(true)
        mainH.postDelayed(::doListen, 1500)
        isRunning = true
        return START_STICKY
    }

    private fun doListen() {
        if (listening) return
        listening = true
        mainH.post { onStatus?.invoke("🎤 Écoute...") }
        val srLang = if (langOther == "auto") "" else SR_LANG[langOther] ?: ""
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (srLang.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, srLang)
        }
        recognizer?.startListening(intent)
    }

    private fun setSpeaker(on: Boolean) {
        (getSystemService(AUDIO_SERVICE) as AudioManager).isSpeakerphoneOn = on
    }

    private fun stopListen() {
        listening = false
        recognizer?.stopListening()
    }

    private suspend fun translateAndSpeak(text: String) {
        try {
            val sl = if (langOther == "auto") "auto" else langOther
            val q  = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$langMoi&dt=t&q=$q"
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val translated = JSONArray(raw).getJSONArray(0).getJSONArray(0).getString(0)
            if (translated.isNotBlank()) mainH.post { speak(translated); onTranslated?.invoke(translated) }
        } catch (e: Exception) { Log.e("TS", e.message ?: "") }
    }

    private fun speak(text: String) {
        val locale = TTS_LOC[langMoi] ?: Locale.getDefault()
        tts?.language = locale
        val params = Bundle()
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "ts${System.currentTimeMillis()}")
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Traduction Appel", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("🌐 Traduction Appel ACTIVE")
            .setContentText("MOI: $langMoi | AUTRE: $langOther — mets sur haut-parleur")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi).build()
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        stopListen()
        recognizer?.destroy()
        tts?.shutdown()
        @Suppress("DEPRECATION")
        (getSystemService(TELEPHONY_SERVICE) as TelephonyManager)
            .listen(phoneListener, PhoneStateListener.LISTEN_NONE)
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null
}
