package com.calltranslate

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class MusicService : Service() {

    companion object {
        private const val NOTIF_ID = 20
        private const val CHANNEL  = "music_play"
    }

    override fun onCreate() {
        super.onCreate()
        val ch = NotificationChannel(CHANNEL, "Musique", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("🎵 Musique")
            .setContentText(MusicPlayer.playlist.getOrNull(MusicPlayer.currentIndex)?.name ?: "—")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).build()
        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onDestroy() {
        MusicPlayer.release()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
