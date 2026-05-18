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
        var onOriginal:    ((String) -> Unit)? = null
        var onTranslated:  ((String) -> Unit)? = null
        var onPartial:     ((String) -> Unit)? = null
        var onStatus:      ((String) -> Unit)? = null
        var onRms:         ((Float) -> Unit)?  = null
        var onCallEnded:   (() -> Unit)?        = null
        var onMoiSaid:     ((String) -> Unit)? = null
        var onMoiTrad:     ((String) -> Unit)? = null
        var onAutreSaid:   ((String) -> Unit)? = null
        var onAutreTrad:   ((String) -> Unit)? = null
        // null=auto-detect, true=force MOI, false=force AUTRE
        @Volatile var forceDirection: Boolean? = null
        private const val NOTIF_ID   = 10
        private const val CHANNEL    = "call_trad"
        const val ACTION_STOP        = "com.calltranslate.STOP_TRAD"
        private val SR_LANG = mapOf(
            "fr" to "fr-FR", "en" to "en-US", "es" to "es-ES",
            "de" to "de-DE", "pt" to "pt-BR", "it" to "it-IT"
        )
        private val TTS_LOC = mapOf(
            "fr" to Locale.FRENCH, "en" to Locale.US, "es" to Locale("es","ES"),
            "de" to Locale.GERMAN, "pt" to Locale("pt","BR"), "it" to Locale.ITALIAN)
    }

    private var langMoi   = "fr"
    private var langOther = "auto"
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var callActive    = false
    private var wasOffhook    = false
    private var listening     = false
    @Volatile private var ttsPlaying = false
    @Volatile private var noMatchRetried = false
    private var listenedInOtherLang = false
    private val mainH = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Suppress("DEPRECATION")
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, number: String?) {
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> { wasOffhook = true; setSpeaker(true) }
                TelephonyManager.CALL_STATE_IDLE    -> {
                    setSpeaker(false)
                    if (wasOffhook) { wasOffhook = false; stopListen(); mainH.post { onCallEnded?.invoke() }; stopSelf() }
                }
            }
        }
    }

    private val recListener = object : RecognitionListener {
        override fun onResults(b: Bundle?) {
            listening = false
            val text = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                noMatchRetried = false
                if (listenedInOtherLang && forceDirection == null) forceDirection = false
                mainH.post { onOriginal?.invoke(text); onStatus?.invoke("✓ Capté") }
                scope.launch { translateAndSpeak(text) }
            } else {
                if (isRunning && !ttsPlaying) mainH.postDelayed({ doListen() }, 500)
            }
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
            if (e == SpeechRecognizer.ERROR_NO_MATCH && forceDirection == null && !noMatchRetried && langOther != "auto") {
                noMatchRetried = true
                mainH.postDelayed({ doListen(otherLang = true) }, 300)
            } else {
                noMatchRetried = false
                if (isRunning && !ttsPlaying) mainH.postDelayed({ doListen() }, 1000)
            }
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
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        langMoi   = intent?.getStringExtra("langMoi")   ?: "fr"
        langOther = intent?.getStringExtra("langOther") ?: "auto"
        try { startForeground(NOTIF_ID, buildNotif()) } catch (e: Exception) { Log.e("TS","notif: ${e.message}") }
        tts = TextToSpeech(this) {}
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(u: String?) {}
            override fun onDone(u: String?) { ttsPlaying = false; if (isRunning) mainH.postDelayed({ doListen() }, 800) }
            @Deprecated("Deprecated in Java")
            override fun onError(u: String?) { ttsPlaying = false; if (isRunning) mainH.postDelayed({ doListen() }, 800) }
        })
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
        mainH.postDelayed({ doListen() }, 1500)
        isRunning = true
        return START_STICKY
    }

    private fun doListen(otherLang: Boolean = false) {
        if (listening || ttsPlaying) return
        listening = true
        listenedInOtherLang = otherLang
        mainH.post { onStatus?.invoke("🎤 Écoute...") }
        val moiLang   = SR_LANG[langMoi] ?: "fr-FR"
        val autreLang = if (langOther == "auto") "en-US" else SR_LANG[langOther] ?: "en-US"
        val srLang = when {
            forceDirection == true  -> moiLang
            forceDirection == false -> autreLang
            otherLang -> autreLang   // auto-retry en langue de l'autre après NO_MATCH
            else -> moiLang
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, srLang)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf<String>())
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
            val q = URLEncoder.encode(text, "UTF-8")
            // Always use sl=auto to get detected language in response
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
            forceDirection = null  // reset après usage
            val isMoiSpeaking = when (dir) {
                true  -> true
                false -> false
                null  -> when {
                    detectedLang.isNotEmpty() -> detectedLang == langMoi
                    else -> translatedToMoi.trim().equals(text.trim(), ignoreCase = true)
                }
            }
            mainH.post { onStatus?.invoke("🔍 détecté:$detectedLang force:$dir moi=$langMoi") }

            if (isMoiSpeaking && langOther != "auto") {
                // Moi parle → traduit vers langue de l'autre
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
                // L'autre parle → traduit vers ma langue
                mainH.post { onAutreSaid?.invoke(text); onOriginal?.invoke(text) }
                mainH.post {
                    speak(translatedToMoi, langMoi)
                    onAutreTrad?.invoke(translatedToMoi); onTranslated?.invoke(translatedToMoi)
                }
            } else {
                // Même texte = boucle ou langue identique, ignorer
                mainH.post { onStatus?.invoke("⚠ ignoré (même langue ou boucle)") }
                if (isRunning) mainH.postDelayed({ doListen() }, 500)
            }
        } catch (e: Exception) { Log.e("TS", e.message ?: "") }
    }

    private fun speak(text: String, lang: String = langMoi) {
        ttsPlaying = true
        stopListen()  // stop SR so TTS output isn't re-captured
        val locale = TTS_LOC[lang] ?: Locale.getDefault()
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
        val stopPI = PendingIntent.getService(this, 1,
            Intent(this, TranslationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("🌐 Traduction Appel ACTIVE")
            .setContentText("MOI: $langMoi | AUTRE: $langOther — mets sur haut-parleur")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "⏹ Arrêter", stopPI)
            .build()
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
