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
        bind(R.id.styleGroup, mapOf(R.id.styleWindow to 3, R.id.styleScale to 0, R.id.styleFade to 1, R.id.styleSlide to 2), p.style) { p.style = it }
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

        // Label size 50%..150% (bar 0..100), home screen + app drawer
        val labelText = findViewById<TextView>(R.id.labelSizeLabel)
        val labelBar = findViewById<SeekBar>(R.id.labelSizeBar)
        labelBar.progress = (p.labelPercent - 50).coerceIn(0, 100)
        labelText.text = "Label size: ${p.labelPercent}%"
        labelBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = 50 + progress
                p.labelPercent = percent
                labelText.text = "Label size: $percent%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        bind(R.id.homeGridGroup, mapOf(R.id.grid4x6 to 0, R.id.grid4x7 to 1, R.id.grid5x6 to 2), p.homeGrid) { p.homeGrid = it }

        bind(R.id.drawerColorGroup, mapOf(R.id.dcAuto to 0, R.id.dcLight to 1, R.id.dcDark to 2), p.drawerColor) { p.drawerColor = it }
        percentBar(R.id.transBar, R.id.transLabel, R.id.transRestore, "Transparency effect", p.drawerTransparency, Prefs.DEFAULT_TRANSPARENCY) { p.drawerTransparency = it }
        percentBar(R.id.blurBar, R.id.blurLabel, R.id.blurRestore, "Blur effect", p.drawerBlur, Prefs.DEFAULT_BLUR) { p.drawerBlur = it }

        findViewById<SwitchCompat>(R.id.dockSwitch).apply {
            isChecked = p.dockEnabled
            setOnCheckedChangeListener { _, checked -> p.dockEnabled = checked }
        }
        findViewById<SwitchCompat>(R.id.dockBgSwitch).apply {
            isChecked = p.dockBackground
            setOnCheckedChangeListener { _, checked -> p.dockBackground = checked }
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

    /** A 0..100 slider with a live "title: N%" label and a Restore button. */
    private fun percentBar(barId: Int, labelId: Int, restoreId: Int, title: String, value: Int, default: Int, save: (Int) -> Unit) {
        val bar = findViewById<SeekBar>(barId)
        val label = findViewById<TextView>(labelId)
        bar.progress = value
        label.text = "$title: $value%"
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                save(progress)
                label.text = "$title: $progress%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        findViewById<Button>(restoreId).setOnClickListener { bar.progress = default }
    }

    private fun bind(groupId: Int, map: Map<Int, Int>, current: Int, save: (Int) -> Unit) {
        val g = findViewById<RadioGroup>(groupId)
        map.entries.firstOrNull { it.value == current }?.let { g.check(it.key) }
        g.setOnCheckedChangeListener { _, id -> map[id]?.let(save) }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
