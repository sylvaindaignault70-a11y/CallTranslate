package com.calltranslate

import android.content.Context
import android.media.audiofx.Equalizer
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment

class EqFragment : Fragment() {

    private val PRESETS = linkedMapOf(
        "Flat"       to intArrayOf(0, 0, 0, 0, 0),
        "Bass"       to intArrayOf(600, 400, 0, 0, 0),
        "Vocal"      to intArrayOf(-200, 0, 300, 300, 100),
        "Rock"       to intArrayOf(400, 100, -100, 200, 300),
        "Jazz"       to intArrayOf(300, 100, 0, 100, 200),
        "Classical"  to intArrayOf(400, 200, 0, 100, 200),
        "Electronic" to intArrayOf(500, 300, 0, 200, 400),
        "Podcast"    to intArrayOf(-200, 0, 200, 300, 100)
    )

    private lateinit var presetContainer: LinearLayout
    private lateinit var bandsContainer: LinearLayout
    private lateinit var tvStatus: TextView
    private val seekBars = mutableListOf<SeekBar>()

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_eq, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        presetContainer = v.findViewById(R.id.presetContainer)
        bandsContainer  = v.findViewById(R.id.bandsContainer)
        tvStatus        = v.findViewById(R.id.tvEQStatus)

        v.findViewById<Button>(R.id.btnResetEQ).setOnClickListener  { applyPreset("Flat") }
        v.findViewById<Button>(R.id.btnSaveEQ).setOnClickListener   { saveEQ() }

        buildUI()
    }

    private fun buildUI() {
        val eq = MusicPlayer.equalizer
        if (eq == null) {
            tvStatus.text = "Lance la musique d'abord pour activer l'EQ"
            return
        }
        tvStatus.text = "${eq.numberOfBands} bandes"

        // Presets
        presetContainer.removeAllViews()
        PRESETS.keys.forEach { name ->
            val btn = Button(requireContext()).apply {
                text = name
                setTextColor(0xFF94a3b8.toInt())
                setBackgroundColor(0xFF1a1a2e.toInt())
                textSize = 11f
                setPadding(20, 8, 20, 8)
                setOnClickListener { applyPreset(name) }
            }
            presetContainer.addView(btn)
        }

        // Band sliders
        bandsContainer.removeAllViews()
        seekBars.clear()
        val minLevel = eq.bandLevelRange[0]
        val maxLevel = eq.bandLevelRange[1]
        val range = maxLevel - minLevel

        for (i in 0 until eq.numberOfBands) {
            val band = i.toShort()
            val freq = eq.getCenterFreq(band) / 1000
            val label = if (freq >= 1000) "${freq/1000}K" else "$freq"

            val col = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(4, 0, 4, 0)
            }

            val tvVal = TextView(requireContext()).apply {
                text = "0"
                textSize = 9f
                setTextColor(0xFF7c3aed.toInt())
                gravity = android.view.Gravity.CENTER
            }

            val sb = SeekBar(requireContext()).apply {
                max = range
                progress = (eq.getBandLevel(band) - minLevel).toInt()
                rotation = 270f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f)
                progressTintList = android.content.res.ColorStateList.valueOf(0xFF7c3aed.toInt())
                thumbTintList = android.content.res.ColorStateList.valueOf(0xFF7c3aed.toInt())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        val lvl = (p + minLevel).toShort()
                        MusicPlayer.setBand(band, lvl)
                        val db = lvl / 100
                        val sign = if (db > 0) "+" else ""
                        tvVal.text = "$sign$db"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) { saveEQ() }
                })
            }
            seekBars.add(sb)

            val tvLbl = TextView(requireContext()).apply {
                text = label
                textSize = 9f
                setTextColor(0xFF64748b.toInt())
                gravity = android.view.Gravity.CENTER
            }

            col.addView(tvVal)
            col.addView(sb)
            col.addView(tvLbl)
            bandsContainer.addView(col)
        }

        loadSavedEQ(eq)
    }

    private fun applyPreset(name: String) {
        val eq = MusicPlayer.equalizer ?: return
        val vals = PRESETS[name] ?: return
        val minLevel = eq.bandLevelRange[0]
        val maxLevel = eq.bandLevelRange[1]
        val range = (maxLevel - minLevel).toInt()

        for (i in 0 until minOf(eq.numberOfBands.toInt(), vals.size)) {
            val band = i.toShort()
            val lvl  = vals[i].toShort().coerceIn(minLevel, maxLevel)
            eq.setBandLevel(band, lvl)
            seekBars.getOrNull(i)?.progress = (lvl - minLevel).toInt().coerceIn(0, range)
        }
        saveEQ()
    }

    private fun saveEQ() {
        val eq = MusicPlayer.equalizer ?: return
        val prefs = requireContext().getSharedPreferences("eq", Context.MODE_PRIVATE).edit()
        for (i in 0 until eq.numberOfBands) {
            prefs.putInt("band_$i", eq.getBandLevel(i.toShort()).toInt())
        }
        prefs.apply()
        tvStatus.text = "✅ EQ sauvegardé"
    }

    private fun loadSavedEQ(eq: Equalizer) {
        val prefs = requireContext().getSharedPreferences("eq", Context.MODE_PRIVATE)
        val minLevel = eq.bandLevelRange[0]
        val maxLevel = eq.bandLevelRange[1]
        val range = (maxLevel - minLevel).toInt()
        for (i in 0 until eq.numberOfBands) {
            if (prefs.contains("band_$i")) {
                val lvl = prefs.getInt("band_$i", 0).toShort().coerceIn(minLevel, maxLevel)
                eq.setBandLevel(i.toShort(), lvl)
                seekBars.getOrNull(i)?.progress = (lvl - minLevel).toInt().coerceIn(0, range)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        buildUI()
    }
}
