package io.github.ghghuser.correctmode

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.view.View

/**
 * "Answer a few questions" instead of knowing what Ringer / DND mean. One question per page:
 * silence? -> vibration? -> restrict (DND)? -> who may reach me? -> which sounds? -> auto release? -> done.
 * It only fills in a Profile; the real work (and the safety checks) happens in Engine when it is turned on.
 * The new profile goes to the left tab (your own), and the editor opens next for name / icon / colours.
 */
class WizardActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        const val SILENT = 1; const val VIB = 2; const val DND = 3; const val WHO = 4; const val SOUNDS = 5; const val AUTO = 6; const val DONE = 7
    }

    private val p = Profile(Store.newId(), "")
    private val history = ArrayList<Int>()
    private var page = SILENT
    private lateinit var body: LinearLayout
    private lateinit var nameEdit: EditText

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p.dnd = Dnd.OFF; p.ringer = Ringer.VIBRATE
        p.media = false; p.mediaOnBt = false; p.alarms = false; p.system = false
        p.duration = Dur.ASK
        body = Ui.vbox(this).apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        setContentView(Ui.screen(this, s(R.string.wiz_title), true, body))
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (history.isEmpty()) super.onBackPressed() else { page = history.removeAt(history.size - 1); render() }
    }

    private fun go(next: Int) { history.add(page); page = next; render() }

    private fun question(title: Int, hint: Int) {
        body.addView(Ui.text(this, s(title), 20f, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) })
        body.addView(Ui.text(this, s(hint), 13f, secondary = true).apply { setPadding(0, 0, 0, dp(12)) })
    }

    private fun option(label: Int, onClick: () -> Unit) {
        body.addView(Ui.button(this, s(label)) { onClick() }, Ui.lp(h = dp(60)).apply { setMargins(0, dp(4), 0, dp(4)) })
    }

    private fun nav(nextLabel: Int? = null, onNext: (() -> Unit)? = null) {
        val row = Ui.hbox(this).apply { setPadding(0, dp(16), 0, 0) }
        if (history.isNotEmpty()) row.addView(Ui.button(this, s(R.string.wiz_back)) { onBackPressed() }, Ui.lp(0, weight = 1f))
        if (nextLabel != null && onNext != null) row.addView(Ui.button(this, s(nextLabel)) { onNext() }, Ui.lp(0, weight = 1f))
        body.addView(row)
    }

    private fun render() {
        body.removeAllViews()
        when (page) {
            SILENT -> {
                question(R.string.wiz_q1, R.string.wiz_q1_hint)
                option(R.string.wiz_q1_yes) { go(VIB) }
                option(R.string.wiz_q1_no) { p.ringer = Ringer.NORMAL; p.dnd = Dnd.OFF; go(DONE) }
                body.addView(Ui.button(this, s(R.string.wiz_skip)) {
                    startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("tab", 0))
                    finish()
                }, Ui.lp().apply { topMargin = dp(16) })
            }
            VIB -> {
                question(R.string.wiz_q2, R.string.wiz_q2_hint)
                option(R.string.wiz_q2_vib) { p.ringer = Ringer.VIBRATE; go(DND) }
                option(R.string.wiz_q2_mute) { p.ringer = Ringer.MUTE; go(DND) }
                nav()
            }
            DND -> {
                question(R.string.wiz_q3, R.string.wiz_q3_hint)
                option(R.string.wiz_q3_off) { p.dnd = Dnd.OFF; go(AUTO) }
                option(R.string.wiz_q3_pri) { p.dnd = Dnd.PRIORITY; go(WHO) }
                // with these two Android overrides the ringer anyway (see Patterns), so the question is moot
                option(R.string.wiz_q3_alarms) { p.dnd = Dnd.ALARMS; p.ringer = Ringer.VIBRATE; go(AUTO) }
                option(R.string.wiz_q3_none) { p.dnd = Dnd.NONE; p.ringer = Ringer.VIBRATE; go(AUTO) }
                nav()
            }
            WHO -> {
                question(R.string.wiz_q4, R.string.wiz_q4_hint)
                val senders = listOf(0, 1, 2, 3).map { Describe.senders(this, it) }
                lateinit var callsBtn: android.widget.Button
                lateinit var msgBtn: android.widget.Button
                fun texts() {
                    callsBtn.text = s(R.string.allow_calls) + ": " + Describe.senders(this, p.calls)
                    msgBtn.text = s(R.string.allow_messages) + ": " + Describe.senders(this, p.messages)
                }
                callsBtn = Ui.button(this, "") { Ui.choose(this, s(R.string.allow_calls), senders, p.calls) { i -> p.calls = i; texts() } }
                msgBtn = Ui.button(this, "") { Ui.choose(this, s(R.string.allow_messages), senders, p.messages) { i -> p.messages = i; texts() } }
                body.addView(callsBtn, Ui.lp(h = dp(60))); body.addView(msgBtn, Ui.lp(h = dp(60)))
                texts()
                nav(R.string.wiz_next) { go(SOUNDS) }
            }
            SOUNDS -> {
                question(R.string.wiz_q5, R.string.wiz_q5_hint)
                body.addView(Ui.text(this, s(R.string.wiz_media), 14f, bold = true))
                val g = RadioGroup(this)
                val labels = listOf(R.string.wiz_media_none, R.string.wiz_media_bt, R.string.wiz_media_any)
                val cur = if (p.media) 2 else if (p.mediaOnBt) 1 else 0
                labels.forEachIndexed { i, l ->
                    g.addView(RadioButton(this).apply {
                        text = s(l); id = View.generateViewId(); isChecked = i == cur
                        setOnClickListener { p.media = i == 2; p.mediaOnBt = i == 1 }
                    })
                }
                body.addView(g)
                body.addView(CheckBox(this).apply {
                    text = s(R.string.allow_alarms); isChecked = p.alarms
                    setOnCheckedChangeListener { _, v -> p.alarms = v }
                })
                body.addView(CheckBox(this).apply {
                    text = s(R.string.allow_system); isChecked = p.system
                    setOnCheckedChangeListener { _, v -> p.system = v }
                })
                nav(R.string.wiz_next) { go(AUTO) }
            }
            AUTO -> {
                question(R.string.wiz_q6, R.string.wiz_q6_hint)
                val options = listOf(Dur.ASK, 30, 60, 120, Dur.NONE)
                val g = RadioGroup(this)
                options.forEach { d ->
                    g.addView(RadioButton(this).apply {
                        text = Ui.durationLabel(this@WizardActivity, d); id = View.generateViewId(); isChecked = p.duration == d
                        setOnClickListener { p.duration = d }
                    })
                }
                body.addView(g)
                nav(R.string.wiz_next) { go(DONE) }
            }
            else -> {
                question(R.string.wiz_q7, R.string.wiz_q7_hint)
                val idx = Patterns.indexOf(p).coerceAtLeast(0)
                body.addView(Widgets.preview(this, p.copy(iconKey = Patterns.ICONS[idx]), false, 72), Ui.lp(dp(72), dp(72)))
                body.addView(Ui.text(this, Describe.chips(p), 15f).apply { setPadding(0, dp(8), 0, 0) })
                body.addView(Ui.text(this, s(R.string.matrix_title) + "\n" + Describe.matrixLines(this, p), 13f).apply { setPadding(0, dp(4), 0, dp(4)) })
                body.addView(Ui.text(this, s(R.string.legend), 11f, secondary = true))
                body.addView(Ui.text(this, s(R.string.name), 13f, secondary = true).apply { setPadding(0, dp(12), 0, 0) })
                nameEdit = EditText(this).apply {
                    setText(s(Patterns.NAMES[idx])); setSingleLine()
                    filters = arrayOf(android.text.InputFilter.LengthFilter(30))
                }
                body.addView(nameEdit)
                nav(R.string.wiz_create) { create(idx) }
            }
        }
    }

    private fun create(idx: Int) {
        p.name = nameEdit.text.toString().trim().ifEmpty { s(R.string.wiz_name_default) }
        p.iconKey = Patterns.ICONS[idx]
        p.tab = 0 // your own profiles live in the left tab
        Store.upsert(this, p)
        Widgets.updateAll(this)
        Schedules.reschedule(this)
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", 0).apply()
        startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("id", p.id))
        finish()
    }
}
