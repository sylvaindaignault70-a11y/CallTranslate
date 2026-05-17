package com.calltranslate

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri

object MusicPlayer {

    var mediaPlayer: MediaPlayer? = null
    var equalizer: Equalizer? = null
    val playlist = mutableListOf<Track>()
    var currentIndex = -1
    var onTrackChange: ((Int) -> Unit)? = null
    var onProgress: ((Int, Int) -> Unit)? = null

    val isPlaying get() = mediaPlayer?.isPlaying == true
    val duration  get() = mediaPlayer?.duration ?: 0
    val position  get() = mediaPlayer?.currentPosition ?: 0

    fun play(ctx: Context, idx: Int) {
        if (idx < 0 || idx >= playlist.size) return
        currentIndex = idx
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(ctx, playlist[idx].uri)
            prepare()
            start()
            setOnCompletionListener { next(ctx) }
        }
        setupEQ()
        onTrackChange?.invoke(idx)
    }

    fun togglePlay() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() else it.start() }
    }

    fun next(ctx: Context) {
        if (playlist.isEmpty()) return
        play(ctx, (currentIndex + 1) % playlist.size)
    }

    fun prev(ctx: Context) {
        if (playlist.isEmpty()) return
        play(ctx, if (currentIndex > 0) currentIndex - 1 else playlist.size - 1)
    }

    fun seekTo(ms: Int) { mediaPlayer?.seekTo(ms) }

    fun shuffle(ctx: Context) {
        if (playlist.isEmpty()) return
        play(ctx, (0 until playlist.size).random())
    }

    private fun setupEQ() {
        val mp = mediaPlayer ?: return
        equalizer?.release()
        equalizer = try {
            Equalizer(0, mp.audioSessionId).apply { enabled = true }
        } catch (e: Exception) { null }
    }

    fun setBand(band: Short, level: Short) {
        equalizer?.setBandLevel(band, level)
    }

    fun release() {
        equalizer?.release(); equalizer = null
        mediaPlayer?.release(); mediaPlayer = null
        currentIndex = -1
    }
}
