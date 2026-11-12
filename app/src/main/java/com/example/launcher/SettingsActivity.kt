package com.example.launcher

import android.os.Bundle
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = "Launcher settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val p = Prefs(this)
        bind(R.id.styleGroup, mapOf(R.id.styleScale to 0, R.id.styleFade to 1, R.id.styleSlide to 2), p.style) { p.style = it }
        bind(R.id.speedGroup, mapOf(R.id.speedFast to 250, R.id.speedNormal to 450, R.id.speedSlow to 800), p.speed) { p.speed = it }
        bind(R.id.colsGroup, mapOf(R.id.cols4 to 4, R.id.cols5 to 5), p.columns) { p.columns = it }
    }

    private fun bind(groupId: Int, map: Map<Int, Int>, current: Int, save: (Int) -> Unit) {
        val g = findViewById<RadioGroup>(groupId)
        map.entries.firstOrNull { it.value == current }?.let { g.check(it.key) }
        g.setOnCheckedChangeListener { _, id -> map[id]?.let(save) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
