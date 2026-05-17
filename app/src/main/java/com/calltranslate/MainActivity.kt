package com.calltranslate

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)

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

    private fun show(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, f)
            .commit()
    }
}
