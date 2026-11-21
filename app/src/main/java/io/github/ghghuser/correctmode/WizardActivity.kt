package io.github.ghghuser.correctmode

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView

/**
 * "Answer a few questions" instead of knowing what Ringer / DND mean. A card floating over the main screen.
 *
 *  1. Is there a sound you do not want?   (vibrate wanted / no vibration / no sound at all / no -> normal)
 *  2. Which sounds? (only for the first two)  one row per source, details open right under the row
 *  3. Auto release / timer
 *  4. Summary + name
 *
 * It only fills in a Profile; the real work (and the safety checks) happens in Engine when it is turned on.
 * The new profile goes to the left tab (your own), and the editor opens next for name / icon / colours.
 */
class WizardActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        const val CHOOSE = 1; const val SOUNDS = 2; const val AUTO = 3; const val DONE = 4
        const val BRANCH_VIB = 0; const val BRANCH_MUTE = 1; const val BRANCH_ALL = 2; const val BRANCH_NORMAL = 3
    }

    private val p = Profile(Store.newId(), "")
    private val history = ArrayList<Int>()
    private var page = CHOOSE
    private var branch = BRANCH_NORMAL

    // answers of page 2: true = this sound is turned OFF (muted)
    private var muteAlarm = false
    private var muteMedia = true
    private var muteTouch = true
    private var muteRem = true
    private var muteEvt = true
    private var timerOn = false

    private lateinit var body: LinearLayout
    private lateinit var stepText: android.widget.TextView
    private lateinit var navRow: LinearLayout
    private lateinit var nameEdit: EditText

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) // fixed size
        p.duration = Dur.ASK
        p.media = false; p.mediaOnBt = true; p.alarms = false; p.system = false; p.reminders = false; p.events = false
        p.calls = 1; p.messages = 1

        val root = Ui.vbox(this).apply { setPadding(dp(28), dp(24), dp(28), dp(20)) }
        stepText = Ui.text(this, "", 13f, secondary = true).apply { gravity = Gravity.CENTER }
        root.addView(stepText, Ui.lp())
        body = Ui.vbox(this)
        root.addView(ScrollView(this).apply { addView(body) }, Ui.lp(h = 0, weight = 1f).apply { topMargin = dp(4) })
        navRow = Ui.hbox(this).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(navRow)
        setContentView(root)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()

    private fun back() {
        if (history.isEmpty()) finish() else { page = history.removeAt(history.size - 1); render() }
    }

    private fun go(next: Int) { history.add(page); page = next; render() }

    // ---- building blocks ------------------------------------------------------------------

    private fun title(text: String, hint: String?) {
        body.addView(Ui.text(this, text, 20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) }, Ui.lp())
        if (hint != null) body.addView(Ui.text(this, hint, 12f, secondary = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }, Ui.lp())
    }

    private fun small(text: String, topDp: Int = 0): android.widget.TextView =
        Ui.text(this, text, 12f, secondary = true).apply { setPadding(dp(4), dp(topDp), dp(4), 0) }

    /** Big option: first line normal, optional second line smaller. */
    private fun option(label: String, sub: String?, onClick: () -> Unit) {
        val t = SpannableStringBuilder(label)
        if (sub != null) {
            t.append("\n").append(sub)
            t.setSpan(RelativeSizeSpan(0.78f), label.length + 1, t.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        body.addView(Ui.button(this, t) { onClick() }.apply { gravity = Gravity.CENTER }, Ui.lp(h = LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(4), 0, dp(4))
        })
    }

    private fun nav(nextLabel: String? = null, onNext: (() -> Unit)? = null) {
        navRow.removeAllViews()
        navRow.addView(Ui.button(this, s(R.string.wiz_cancel)) { finish() }, Ui.lp(0, weight = 1f))
        if (history.isNotEmpty()) navRow.addView(Ui.button(this, s(R.string.wiz_back)) { back() }, Ui.lp(0, weight = 1f))
        if (nextLabel != null && onNext != null) navRow.addView(Ui.button(this, nextLabel) { onNext() }, Ui.lp(0, weight = 1f))
    }

    private fun totalSteps(): Int = if (branch == BRANCH_VIB || branch == BRANCH_MUTE) 4 else 3

    private fun stepNo(): Int = history.size + 1

    private fun render() {
        body.removeAllViews()
        stepText.text = if (page == CHOOSE) s(R.string.wiz_step1, 1) else s(R.string.wiz_step, stepNo(), totalSteps())
        when (page) {
            CHOOSE -> renderChoose()
            SOUNDS -> renderSounds()
            AUTO -> renderAuto()
            else -> renderDone()
        }
    }

    // ---- page 1 ---------------------------------------------------------------------------

    private fun renderChoose() {
        title(s(R.string.wiz_q1), s(R.string.wiz_q1_hint))
        option(s(R.string.wiz_a_vib), s(R.string.wiz_a_vib_sub)) { branch = BRANCH_VIB; go(SOUNDS) }
        option(s(R.string.wiz_a_mute), s(R.string.wiz_a_mute_sub)) { branch = BRANCH_MUTE; go(SOUNDS) }
        option(s(R.string.wiz_a_all), s(R.string.wiz_a_all_sub)) {
            branch = BRANCH_ALL
            p.ringer = Ringer.MUTE; p.dnd = Dnd.NONE; p.mediaOnBt = false // no sound at all, earphones included
            go(AUTO)
        }
        option(s(R.string.wiz_a_none), s(R.string.wiz_a_none_sub)) {
            branch = BRANCH_NORMAL
            p.ringer = Ringer.NORMAL; p.dnd = Dnd.OFF; p.mediaOnBt = false
            go(AUTO)
        }
        body.addView(small(s(R.string.wiz_foot1), 10), Ui.lp())
        nav()
        body.addView(Ui.button(this, s(R.string.wiz_skip)) {
            startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("tab", 0))
            finish()
        }, Ui.lp().apply { topMargin = dp(12) })
    }

    // ---- page 2: which sounds? every row has its details right under it -------------------

    private fun muteRow(label: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit, extra: ((LinearLayout, Boolean) -> Unit)? = null) {
        val box = Ui.vbox(this).apply { setPadding(0, dp(2), 0, dp(2)) }
        val detail = Ui.vbox(this).apply { setPadding(dp(36), 0, 0, 0) }
        fun fill(on: Boolean) { detail.removeAllViews(); extra?.invoke(detail, on) }
        box.addView(CheckBox(this).apply {
            text = "🔇 $label"; isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v); fill(v) }
        })
        if (note != null) box.addView(small(note).apply { setPadding(dp(36), 0, dp(4), 0) })
        box.addView(detail)
        fill(checked)
        body.addView(box, Ui.lp())
    }

    private fun renderSounds() {
        val vib = branch == BRANCH_VIB
        title(s(R.string.wiz_q2), s(if (vib) R.string.wiz_q2_hint_vib else R.string.wiz_q2_hint_mute))

        // calls / messages
        val callBox = Ui.vbox(this).apply { setPadding(0, dp(4), 0, dp(6)) }
        if (vib) {
            callBox.addView(Ui.text(this, "📳 " + s(R.string.wiz_row_calls), 15f, bold = true))
            callBox.addView(small(s(R.string.wiz_calls_sub)).apply { setPadding(dp(4), 0, dp(4), dp(4)) })
            val senders = listOf(0, 1, 2, 3).map { Describe.senders(this, it) }
            lateinit var callsBtn: Button
            lateinit var msgBtn: Button
            fun texts() {
                callsBtn.text = s(R.string.allow_calls) + ": " + Describe.senders(this, p.calls)
                msgBtn.text = s(R.string.allow_messages) + ": " + Describe.senders(this, p.messages)
            }
            callsBtn = Ui.button(this, "") { Ui.choose(this, s(R.string.allow_calls), senders, p.calls) { i -> p.calls = i; texts() } }
            msgBtn = Ui.button(this, "") { Ui.choose(this, s(R.string.allow_messages), senders, p.messages) { i -> p.messages = i; texts() } }
            texts()
            val r = Ui.hbox(this)
            r.addView(callsBtn, Ui.lp(0, weight = 1f)); r.addView(msgBtn, Ui.lp(0, weight = 1f))
            callBox.addView(r)
        } else {
            callBox.addView(Ui.text(this, "🔇 " + s(R.string.wiz_row_calls), 15f, bold = true))
            callBox.addView(small(s(R.string.wiz_calls_mute_note)))
        }
        body.addView(callBox, Ui.lp())

        // apps: not a per-profile setting
        val appBox = Ui.vbox(this).apply { setPadding(0, dp(2), 0, dp(6)) }
        appBox.addView(Ui.text(this, "🔇 " + s(R.string.wiz_row_apps), 15f, bold = true))
        appBox.addView(small(s(R.string.wiz_apps_note)))
        appBox.addView(Ui.button(this, s(R.string.apps_open_btn)) { Ui.openPrioritySettings(this) })
        body.addView(appBox, Ui.lp())

        muteRow(s(R.string.wiz_row_alarm), s(R.string.wiz_alarm_note), muteAlarm, { muteAlarm = it })
        muteRow(s(R.string.wiz_row_media), s(R.string.wiz_media_note), muteMedia, { muteMedia = it }) { box, on ->
            if (on) box.addView(CheckBox(this).apply {
                text = s(R.string.wiz_media_bt_opt); isChecked = p.mediaOnBt
                setOnCheckedChangeListener { _, v -> p.mediaOnBt = v }
            })
        }
        muteRow(s(R.string.wiz_row_touch), null, muteTouch, { muteTouch = it })
        muteRow(s(R.string.wiz_row_rem), null, muteRem, { muteRem = it })
        muteRow(s(R.string.wiz_row_evt), null, muteEvt, { muteEvt = it })
        body.addView(small(s(if (vib) R.string.wiz_foot2 else R.string.wiz_foot2_mute), 8), Ui.lp())
        nav(s(R.string.wiz_next)) { applySounds(); go(AUTO) }
    }

    /** Turns the answers of page 2 into the profile. Unchecked sounds are the ones that stay allowed. */
    private fun applySounds() {
        val vib = branch == BRANCH_VIB
        p.ringer = if (vib) Ringer.VIBRATE else Ringer.MUTE
        if (!vib) { p.calls = 0; p.messages = 0 } // with the ringer muted nothing could sound anyway
        p.alarms = !muteAlarm
        p.media = !muteMedia
        if (!muteMedia) p.mediaOnBt = false
        p.system = !muteTouch
        p.reminders = !muteRem
        p.events = !muteEvt
        val nothingAllowed = !p.alarms && !p.media && !p.mediaOnBt && !p.system && !p.reminders && !p.events &&
            p.calls == 0 && p.messages == 0
        val nothingMuted = vib && p.alarms && p.media && p.system && p.reminders && p.events && p.calls == 1 && p.messages == 1
        p.dnd = when {
            nothingAllowed -> Dnd.NONE            // everything off, alarms included
            nothingMuted -> Dnd.OFF               // plain "vibrate"
            else -> Dnd.PRIORITY
        }
        if (p.dnd == Dnd.NONE) p.ringer = Ringer.MUTE
    }

    // ---- page 3 ---------------------------------------------------------------------------

    private fun renderAuto() {
        title(s(R.string.wiz_q6), s(R.string.wiz_q6_hint))
        val g = RadioGroup(this)
        listOf(Dur.ASK, 30, 60, 120, Dur.NONE).forEach { d ->
            g.addView(RadioButton(this).apply {
                text = Ui.durationLabel(this@WizardActivity, d); id = View.generateViewId(); isChecked = p.duration == d
                setOnClickListener { p.duration = d }
            })
        }
        body.addView(g)
        body.addView(CheckBox(this).apply {
            text = s(R.string.wiz_timer_check); isChecked = timerOn
            setOnCheckedChangeListener { _, v -> timerOn = v }
        }, Ui.lp().apply { topMargin = dp(8) })
        body.addView(small(s(R.string.wiz_timer_hint)), Ui.lp())
        nav(s(R.string.wiz_next)) { go(DONE) }
    }

    // ---- page 4 ---------------------------------------------------------------------------

    private fun renderDone() {
        title(s(R.string.wiz_q7), s(R.string.wiz_q7_hint))
        val idx = Patterns.indexOf(p)
        val iconKey = if (idx >= 0) Patterns.ICONS[idx] else "bell_off"
        val shown = p.copy(iconKey = iconKey)
        body.addView(Widgets.preview(this, shown, false, 72), Ui.lp(dp(72), dp(72)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        body.addView(Ui.text(this, Describe.summary(this, p), 13f).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }, Ui.lp())
        body.addView(Ui.chipsView(this, p, centered = true), Ui.lp())
        body.addView(Ui.matrixView(this, p).apply { setPadding(0, dp(6), 0, dp(4)) }, Ui.lp())
        body.addView(Ui.text(this, Describe.detail(this, p), 12f, secondary = true), Ui.lp())
        body.addView(Ui.text(this, s(R.string.legend), 11f, secondary = true).apply { setPadding(0, dp(4), 0, 0) }, Ui.lp())
        body.addView(Ui.text(this, s(R.string.name), 13f, secondary = true).apply { setPadding(0, dp(12), 0, 0) }, Ui.lp())
        nameEdit = EditText(this).apply {
            setText(if (idx >= 0) s(Patterns.NAMES[idx]) else s(R.string.wiz_name_default)); setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(30))
        }
        body.addView(nameEdit, Ui.lp())
        nav(s(R.string.wiz_create)) { create(iconKey) }
    }

    private fun create(iconKey: String) {
        p.name = nameEdit.text.toString().trim().ifEmpty { s(R.string.wiz_name_default) }
        p.iconKey = iconKey
        p.tab = 0 // your own profiles live in the left tab
        p.schedOn = timerOn // the time itself is set in the editor that opens next
        Store.upsert(this, p)
        Widgets.updateAll(this)
        Schedules.reschedule(this)
        try { getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", 0).apply() } catch (e: Exception) { }
        startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("id", p.id))
        finish()
    }
}
