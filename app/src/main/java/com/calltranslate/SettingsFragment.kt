package com.calltranslate

import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    private lateinit var etApiKey: EditText
    private lateinit var tvStatus: TextView
    private var keyVisible = false

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        etApiKey = v.findViewById(R.id.etApiKey)
        tvStatus = v.findViewById(R.id.tvSettingsStatus)

        val prefs = requireContext().getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("openai_key", ""))

        v.findViewById<Button>(R.id.btnToggleKey).setOnClickListener {
            keyVisible = !keyVisible
            etApiKey.transformationMethod =
                if (keyVisible) HideReturnsTransformationMethod.getInstance()
                else PasswordTransformationMethod.getInstance()
            etApiKey.setSelection(etApiKey.text.length)
        }

        v.findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            val key = etApiKey.text.toString().trim()
            prefs.edit().putString("openai_key", key).apply()
            tvStatus.text = if (key.startsWith("sk-")) "✓ Clef sauvegardée" else "⚠ Format invalide (doit commencer par sk-)"
            tvStatus.setTextColor(if (key.startsWith("sk-")) 0xFF10b981.toInt() else 0xFFf59e0b.toInt())
        }
    }

    companion object {
        fun getApiKey(ctx: android.content.Context): String =
            ctx.getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
                .getString("openai_key", "") ?: ""
    }
}
