package com.calltranslate

import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MusiqueFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: TrackAdapter
    private lateinit var playerBar: View
    private lateinit var tvNow: TextView
    private lateinit var btnPlay: Button
    private lateinit var seekBar: SeekBar
    private lateinit var tvNow2: TextView
    private lateinit var tvDur: TextView
    private lateinit var tvStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val updateProgress = object : Runnable {
        override fun run() {
            MusicPlayer.mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    val pos = mp.currentPosition
                    val dur = mp.duration
                    if (dur > 0) {
                        seekBar.progress = (pos * 100 / dur)
                        tvNow2.text = fmtTime(pos)
                        tvDur.text  = fmtTime(dur)
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            try { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        }
        loadUris(uris)
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        try { requireContext().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
        requireContext().getSharedPreferences("music", android.content.Context.MODE_PRIVATE)
            .edit().putString("folderUri", uri.toString()).remove("uris").apply()
        val loaded = loadFromFolderUri(uri, autoPlay = true)
        if (!loaded) pickFiles.launch(arrayOf("audio/*"))
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_musique, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        recycler  = v.findViewById(R.id.recyclerPlaylist)
        playerBar = v.findViewById(R.id.playerBar)
        tvNow     = v.findViewById(R.id.tvNowPlaying)
        btnPlay   = v.findViewById(R.id.btnPlayPause)
        seekBar   = v.findViewById(R.id.seekBar)
        tvNow2    = v.findViewById(R.id.tvTimeNow)
        tvDur     = v.findViewById(R.id.tvTimeDur)
        tvStatus  = v.findViewById(R.id.tvMusicStatus)

        adapter = TrackAdapter(mutableListOf()) { idx -> play(idx) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        if (MusicPlayer.playlist.isNotEmpty()) {
            adapter.setTracks(MusicPlayer.playlist)
            adapter.setCurrentIndex(MusicPlayer.currentIndex)
            showPlayerBar()
        } else {
            restorePlaylist()
        }

        MusicPlayer.onTrackChange = { idx ->
            requireActivity().runOnUiThread {
                adapter.setCurrentIndex(idx)
                tvNow.text = MusicPlayer.playlist.getOrNull(idx)?.name ?: "—"
                btnPlay.text = "⏸"
            }
        }

        v.findViewById<Button>(R.id.btnOpenFolder).setOnClickListener { pickFolder.launch(null) }
        v.findViewById<Button>(R.id.btnOpenFiles).setOnClickListener  { pickFiles.launch(arrayOf("audio/*")) }
        v.findViewById<Button>(R.id.btnShuffle).setOnClickListener    { MusicPlayer.shuffle(requireContext()) }
        v.findViewById<Button>(R.id.btnPrev).setOnClickListener       { MusicPlayer.prev(requireContext()) }
        v.findViewById<Button>(R.id.btnNext).setOnClickListener       { MusicPlayer.next(requireContext()) }

        btnPlay.setOnClickListener {
            MusicPlayer.togglePlay()
            btnPlay.text = if (MusicPlayer.isPlaying) "⏸" else "▶"
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) MusicPlayer.seekTo(MusicPlayer.duration * p / 100)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        handler.post(updateProgress)
    }

    private fun play(idx: Int) {
        MusicPlayer.play(requireContext(), idx)
        showPlayerBar()
        btnPlay.text = "⏸"
        tvStatus.text = "${MusicPlayer.playlist.size} pistes"
        requireContext().getSharedPreferences("music", android.content.Context.MODE_PRIVATE)
            .edit().putInt("lastIndex", idx).apply()
    }

    private fun showPlayerBar() {
        playerBar.visibility = View.VISIBLE
        val cur = MusicPlayer.currentIndex
        if (cur >= 0) tvNow.text = MusicPlayer.playlist.getOrNull(cur)?.name ?: "—"
        btnPlay.text = if (MusicPlayer.isPlaying) "⏸" else "▶"
    }

    private fun loadUris(uris: List<Uri>, autoPlay: Boolean = true) {
        val ctx = requireContext()
        val tracks = uris.mapNotNull { uri ->
            val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && col >= 0) c.getString(col).replace(Regex("\\.[^.]+$"), "") else null
            } ?: uri.lastPathSegment ?: "Track"
            Track(name, uri)
        }.sortedBy { it.name }
        MusicPlayer.playlist.clear()
        MusicPlayer.playlist.addAll(tracks)
        adapter.setTracks(tracks)
        tvStatus.text = "${tracks.size} pistes"
        if (tracks.isNotEmpty()) {
            if (autoPlay) play(0) else {
                val saved = requireContext().getSharedPreferences("music", android.content.Context.MODE_PRIVATE)
                    .getInt("lastIndex", 0).coerceIn(0, tracks.size - 1)
                MusicPlayer.currentIndex = saved
                adapter.setCurrentIndex(saved)
                showPlayerBar()
            }
        }
    }

    private fun loadFromFolderUri(uri: Uri, autoPlay: Boolean): Boolean {
        val treeDocId = try { android.provider.DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return false }
        val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeDocId)
        val uris = mutableListOf<Uri>()
        try { requireContext().contentResolver.query(childrenUri,
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null) } catch (e: Exception) { null }?.use { c ->
            val idCol   = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeCol = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val mime = if (mimeCol >= 0) c.getString(mimeCol) else ""
                if (mime.startsWith("audio/")) {
                    val docId = if (idCol >= 0) c.getString(idCol) else continue
                    uris.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId))
                }
            }
        }
        if (uris.isEmpty()) return false
        loadUris(uris, autoPlay)
        return true
    }

    private fun restorePlaylist() {
        val prefs = requireContext().getSharedPreferences("music", android.content.Context.MODE_PRIVATE)
        val folderUriStr = prefs.getString("folderUri", null)
        if (folderUriStr != null) {
            loadFromFolderUri(Uri.parse(folderUriStr), autoPlay = false)
            return
        }
        val saved = prefs.getString("uris", null) ?: return
        val uris = saved.split("|").filter { it.isNotEmpty() }.map { Uri.parse(it) }
        if (uris.isEmpty()) return
        loadUris(uris, autoPlay = false)
    }

    private fun fmtTime(ms: Int): String {
        val s = ms / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2,'0')}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(updateProgress)
        MusicPlayer.onTrackChange = null
    }
}
