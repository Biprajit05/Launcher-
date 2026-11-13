package com.example.launcher

import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

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
        bind(
            R.id.shapeGroup,
            mapOf(
                R.id.shapeOriginal to 0,
                R.id.shapeCircle to 1,
                R.id.shapeSquircle to 2,
                R.id.shapeRounded to 3,
                R.id.shapeSquare to 4,
                R.id.shapeTeardrop to 5
            ),
            p.shape
        ) { p.shape = it }

        // Icon size 60%..140% (bar 0..80)
        val label = findViewById<TextView>(R.id.sizeLabel)
        val bar = findViewById<SeekBar>(R.id.sizeBar)
        bar.progress = (p.iconPercent - 60).coerceIn(0, 80)
        label.text = "Icon size: ${p.iconPercent}%"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = 60 + progress
                p.iconPercent = percent
                label.text = "Icon size: $percent%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<SwitchCompat>(R.id.dockSwitch).apply {
            isChecked = p.dockEnabled
            setOnCheckedChangeListener { _, checked -> p.dockEnabled = checked }
        }
        findViewById<Button>(R.id.resetDock).setOnClickListener {
            p.setDock(null)
            Toast.makeText(this, "Dock reset", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.resetHome).setOnClickListener {
            p.setHomeLayout(null)
            Toast.makeText(this, "Home screen reset", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bind(groupId: Int, map: Map<Int, Int>, current: Int, save: (Int) -> Unit) {
        val g = findViewById<RadioGroup>(groupId)
        map.entries.firstOrNull { it.value == current }?.let { g.check(it.key) }
        g.setOnCheckedChangeListener { _, id -> map[id]?.let(save) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
