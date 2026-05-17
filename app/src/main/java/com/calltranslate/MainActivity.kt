package com.calltranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val reqPerms = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions handled per-feature */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestAllPermissions()

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        ViewCompat.setOnApplyWindowInsetsListener(nav) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        if (savedInstanceState == null) show(AppelFragment())
        nav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_appel   -> { show(AppelFragment());      true }
                R.id.nav_trad    -> { show(TraductionFragment()); true }
                R.id.nav_musique -> { show(MusiqueFragment());    true }
                R.id.nav_eq      -> { show(EqFragment());         true }
                else -> false
            }
        }
    }

    private fun requestAllPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.READ_MEDIA_AUDIO)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) reqPerms.launch(needed.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            stopService(Intent(this, TranslationService::class.java))
            stopService(Intent(this, MusicService::class.java))
        }
    }

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, f)
            .commit()
    }
}
