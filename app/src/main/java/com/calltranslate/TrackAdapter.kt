package com.calltranslate

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class Track(val name: String, val uri: Uri)

class TrackAdapter(
    private val tracks: MutableList<Track>,
    private val onPlay: (Int) -> Unit
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    @JvmField var currentIndex = -1

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num  : TextView = v.findViewById(R.id.tvTrackNum)
        val name : TextView = v.findViewById(R.id.tvTrackName)
        val play : Button   = v.findViewById(R.id.btnPlay)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val t = tracks[pos]
        h.num.text  = "${pos + 1}"
        h.name.text = t.name
        h.itemView.setBackgroundColor(
            if (pos == currentIndex) 0xFF1a1a2e.toInt() else 0xFF080810.toInt()
        )
        h.play.setOnClickListener { onPlay(pos) }
    }

    override fun getItemCount() = tracks.size

    fun setTracks(list: List<Track>) {
        tracks.clear()
        tracks.addAll(list)
        notifyDataSetChanged()
    }

    fun setCurrentIndex(idx: Int) {
        val old = currentIndex
        currentIndex = idx
        if (old >= 0) notifyItemChanged(old)
        if (idx >= 0) notifyItemChanged(idx)
    }
}
