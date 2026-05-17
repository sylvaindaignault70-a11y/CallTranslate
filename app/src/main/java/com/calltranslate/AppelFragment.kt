package com.calltranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class AppelFragment : Fragment() {

    companion object {
        val LANGS_MOI   = listOf("fr" to "🇫🇷 Français", "en" to "🇬🇧 Anglais")
        val LANGS_OTHER = listOf(
            "auto" to "🔍 Auto", "fr" to "🇫🇷 Français",
            "en" to "🇬🇧 Anglais", "es" to "🇪🇸 Espagnol"
        )
    }

    private var selectedTel = ""
    private var pendingTel  = ""

    private lateinit var spinMoi: Spinner
    private lateinit var spinOther: Spinner
    private lateinit var btnTrad: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvNum: TextView
    private lateinit var etNumero: EditText

    private val requestCallPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingTel.isNotEmpty()) { dialNow(pendingTel); pendingTel = "" }
        else if (!granted) Toast.makeText(requireContext(), "Permission appel refusée", Toast.LENGTH_SHORT).show()
    }

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.PickContact()
    ) { uri ->
        uri ?: return@registerForActivityResult
        loadContact(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_appel, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        super.onViewCreated(v, s)

        spinMoi   = v.findViewById(R.id.spinCallMoi)
        spinOther = v.findViewById(R.id.spinCallOther)
        btnTrad   = v.findViewById(R.id.btnCallTrad)
        tvStatus  = v.findViewById(R.id.tvCallStatus)
        tvNum     = v.findViewById(R.id.tvContactNum)
        etNumero  = v.findViewById(R.id.etNumero)

        spinMoi.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, LANGS_MOI.map { it.second })
        spinOther.adapter = ArrayAdapter(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, LANGS_OTHER.map { it.second })

        val prefs = requireContext().getSharedPreferences("prefs", android.content.Context.MODE_PRIVATE)
        spinMoi.setSelection(LANGS_MOI.indexOfFirst { it.first == prefs.getString("callLangMoi","fr") }.coerceAtLeast(0))
        spinOther.setSelection(LANGS_OTHER.indexOfFirst { it.first == prefs.getString("callLangOther","auto") }.coerceAtLeast(0))

        btnTrad.setOnClickListener { toggleTrad() }
        v.findViewById<Button>(R.id.btnContacts).setOnClickListener { pickContact.launch(null) }
        v.findViewById<Button>(R.id.btnVider).setOnClickListener { vider() }
        v.findViewById<Button>(R.id.btnSignaler).setOnClickListener {
            val num = etNumero.text.toString().trim()
            if (num.isNotEmpty()) signaler(num)
        }

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

    private fun loadContact(uri: Uri) {
        val ctx = requireContext()
        val contactId = ctx.contentResolver.query(
            uri, arrayOf(ContactsContract.Contacts._ID), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getLong(c.getColumnIndexOrThrow(ContactsContract.Contacts._ID)).toString()
            else null
        } ?: return

        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId), null
        )?.use {
            if (it.moveToFirst()) {
                val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val tel  = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                if (tel.isNotEmpty()) {
                    selectedTel = tel.replace("\\s".toRegex(), "")
                    tvNum.text = "📞 $name — $tel"
                    signaler(selectedTel)
                }
            }
        }
    }

    private fun signaler(tel: String) {
        val ctx = requireContext()
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            dialNow(tel)
        } else {
            pendingTel = tel
            requestCallPerm.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun dialNow(tel: String) {
        startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$tel")))
    }

    private fun vider() {
        selectedTel = ""
        tvNum.text = ""
        etNumero.text?.clear()
    }
}
