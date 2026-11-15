package io.github.ghghuser.correctmode

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.SeekBar
import android.widget.TextView

/** A free colour chooser without any library: hue, saturation, brightness and a preview. */
object ColorPicker {
    fun show(a: Activity, initial: Int, onPick: (Int) -> Unit) {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial or 0xFF000000.toInt(), hsv)
        val box = Ui.vbox(a).apply { setPadding(a.dp(20), a.dp(12), a.dp(20), 0) }
        val preview = View(a)
        val hex: TextView = Ui.text(a, "", 14f)
        box.addView(preview, Ui.lp(h = a.dp(56)))
        box.addView(hex)

        fun refresh() {
            val c = Color.HSVToColor(hsv)
            preview.background = GradientDrawable().apply {
                cornerRadius = a.dp(12).toFloat(); setColor(c); setStroke(a.dp(1), 0x66808080)
            }
            hex.text = String.format("#%06X", c and 0xFFFFFF)
        }

        fun slider(label: Int, max: Int, start: Int, set: (Int) -> Unit) {
            box.addView(Ui.text(a, a.getString(label), 12f, secondary = true).apply { setPadding(0, a.dp(8), 0, 0) })
            box.addView(SeekBar(a).apply {
                this.max = max; progress = start
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) { set(value); refresh() }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }
        slider(R.string.picker_hue, 359, hsv[0].toInt()) { hsv[0] = it.toFloat() }
        slider(R.string.picker_sat, 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f }
        slider(R.string.picker_val, 100, (hsv[2] * 100).toInt()) { hsv[2] = it / 100f }
        refresh()

        AlertDialog.Builder(a).setTitle(a.getString(R.string.picker_title)).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> onPick(Color.HSVToColor(hsv) or 0xFF000000.toInt()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
