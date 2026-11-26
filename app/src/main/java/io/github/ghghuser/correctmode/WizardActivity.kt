package io.github.ghghuser.correctmode

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * "Mode finder": a few questions instead of knowing what Ringer / DND mean. A card floating over the main screen.
 * Every question needs an answer and the "Next" button (an answer alone does not move on).
 *
 *  1. Ring / notification sound wanted?       (yes / no / depends)
 *  2. Vibration wanted?                       (asked every time; a ringing phone vibrates as the phone settings say, so for a
 *                                              "ring" profile the answer only decides the notes and the tick on a widget tap)
 *  3. Bluetooth audio: listen on the connected device?  (yes: all devices / headsets only)
 *  4. Which sounds may still be heard while quiet?      (none / all / pick one by one)
 *  5. The list to pick from                   (only when "pick one by one"; the number of questions is counted again)
 *  6. Switch on / off by itself at a set time?
 *  7. On release: put the ringer and DND back, or release both?
 *  8. On release: what to do with the volumes?
 *  Result: one profile per case ("depends" makes one per case), several can be ticked. Profiles that already exist are not
 *  made again. The name is the name of the behaviour plus emojis; a long press renames it.
 *
 * It only fills in Profiles; the real work (and the safety checks) happens in Engine when one is turned on.
 * New profiles go to the left tab (your own). One new profile opens in the editor next; several go back to the list.
 */
class WizardActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        const val P_SOUND = 1; const val P_VIB = 2; const val P_BT = 3; const val P_MODE = 4; const val P_LIST = 5
        const val P_AUTO = 6; const val P_RESTORE = 7; const val P_VOL = 8; const val P_DONE = 9
        const val YES = 0; const val NO = 1; const val DEPENDS = 2
        const val MODE_CUSTOM = 0; const val MODE_NONE = 1; const val MODE_ALL = 2
        const val UNSET = -1
        const val GAP_DP = 8
    }

    // answers
    private var sound = UNSET
    private var vib = UNSET
    private var tapVibe = true
    private var bt = UNSET // YES / NO
    private var btScope = UNSET
    private var mode = UNSET
    private var callsOn = false; private var callsWho = 1
    private var msgsOn = false; private var msgsWho = 1
    private var media = false; private var alarms = false; private var system = false
    private var rem = false; private var evt = false
    private var autoOn = false
    private var autoOff = false
    private var restore = true // true = put back what was there; false = release both
    private var volMode = VolMode.OFF
    private var volFixed: List<Int> = listOf(50, 50, 50, 50)

    // result page
    private val picked = HashSet<Int>()
    private var pickedInit = false
    private val customNames = HashMap<Int, String>()

    private val history = ArrayList<Int>()
    private var page = P_SOUND

    private lateinit var body: LinearLayout
    private lateinit var stepText: TextView

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) // fixed size
        val root = Ui.vbox(this).apply { setPadding(dp(28), dp(24), dp(28), dp(20)) }
        stepText = Ui.text(this, "", 14f, secondary = true).apply { gravity = Gravity.CENTER }
        root.addView(stepText, Ui.lp())
        body = Ui.vbox(this)
        root.addView(ScrollView(this).apply { addView(body) }, Ui.lp(h = 0, weight = 1f).apply { topMargin = dp(4) })
        setContentView(root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        render()
    }

    private fun back() {
        if (history.isEmpty()) { finish(); return }
        if (page == P_DONE) { pickedInit = false; customNames.clear() }
        page = history.removeAt(history.size - 1)
        render()
    }

    private fun go(next: Int) { history.add(page); page = next; render() }

    /** Back to an earlier page (the answers stay). */
    private fun jumpTo(target: Int) {
        while (history.isNotEmpty() && page != target) page = history.removeAt(history.size - 1)
        render()
    }

    // ---- the order of the questions -------------------------------------------------------

    /** The numbered questions in order. The list question only exists for "pick one by one". */
    private fun questionPages(): List<Int> = buildList {
        add(P_SOUND); add(P_VIB); add(P_BT); add(P_MODE)
        if (mode == UNSET || mode == MODE_CUSTOM) add(P_LIST)
        add(P_AUTO); add(P_RESTORE); add(P_VOL)
    }

    private fun after(pg: Int): Int = when (pg) {
        P_SOUND -> P_VIB
        P_VIB -> P_BT
        P_BT -> P_MODE
        P_MODE -> if (mode == MODE_CUSTOM) P_LIST else P_AUTO
        P_LIST -> P_AUTO
        P_AUTO -> P_RESTORE
        P_RESTORE -> P_VOL
        else -> P_DONE
    }

    // ---- derived answers ------------------------------------------------------------------

    /** The ringer modes to make a profile for. "Depends" means every case that can apply. */
    private fun ringers(): List<Int> {
        val out = ArrayList<Int>()
        if (sound == YES || sound == DEPENDS) out.add(Ringer.NORMAL)
        if (sound != YES) {
            if (vib == YES || vib == DEPENDS) out.add(Ringer.VIBRATE)
            if (vib == NO || vib == DEPENDS) out.add(Ringer.MUTE)
        }
        return out
    }

    /** No sound and no vibration in any case: allowed calls / messages could never alert. */
    private fun mutedOnly(): Boolean = ringers() == listOf(Ringer.MUTE)

    private fun nothingChecked(): Boolean = !callsOn && !msgsOn && !media && !alarms && !system && !rem && !evt

    // ---- building blocks (one spacing and one look for every button) -------------------------------

    private fun title(text: String) {
        body.addView(Ui.text(this, text, 20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(8)) }, Ui.lp())
    }

    private fun label(text: String): TextView =
        Ui.text(this, text, 15f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(GAP_DP * 2), 0, 0) }

    private fun small(text: String): TextView =
        Ui.text(this, text, 12f, secondary = true).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(GAP_DP), dp(4), 0) }

    /** Views side by side with the same gap everywhere. */
    private fun rowOf(vararg v: View): LinearLayout {
        val r = Ui.hbox(this)
        v.forEachIndexed { i, view -> r.addView(view, Ui.lp(0, weight = 1f).apply { if (i > 0) marginStart = dp(GAP_DP) }) }
        return r
    }

    private fun addRow(vararg v: View) { body.addView(rowOf(*v), Ui.lp().apply { topMargin = dp(GAP_DP) }) }

    /** An answer button: outlined, the chosen one is filled. Not a Button, so every answer looks the same. */
    private fun choice(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit): TextView =
        Ui.text(this, text, 15f, bold = selected).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                if (selected) { setColor(0x331F6FEB); setStroke(dp(2), 0xFF1F6FEB.toInt()) } else setStroke(dp(1), 0x55808080)
            }
            isSelected = selected
            contentDescription = text
            stateDescription = if (selected) s(R.string.selected_state) else null // read out by screen readers
            if (enabled) { isClickable = true; setOnClickListener { onClick() } } else alpha = 0.4f
        }

    private fun yesNo(current: Int, yes: Int, no: Int, onPick: (Int) -> Unit) {
        addRow(choice(s(yes), current == YES) { onPick(YES) }, choice(s(no), current == NO) { onPick(NO) })
    }

    private fun answers3(current: Int, onPick: (Int) -> Unit) {
        yesNo(current, R.string.wiz_yes, R.string.wiz_no, onPick)
        addRow(choice(s(R.string.wiz_depends), current == DEPENDS) { onPick(DEPENDS) })
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
        if (!on) v.isClickable = false
    }

    private fun grey(v: View, off: Boolean) { v.alpha = if (off) 0.4f else 1f; setEnabledDeep(v, !off) }

    /**
     * Back / Next (Cancel alone next to Next on the first page, under the row on the others). "Next" stays greyed until
     * the question is answered. [between] sits between the two rows.
     */
    private fun nav(canNext: Boolean, nextLabel: String = s(R.string.wiz_next), between: View? = null, onNext: () -> Unit) {
        val next = Ui.button(this, nextLabel) { onNext() }
        grey(next, !canNext)
        val cancel = Ui.button(this, s(R.string.wiz_cancel)) { finish() }
        if (history.isEmpty()) {
            addRow(cancel, next)
            if (between != null) body.addView(between, Ui.lp())
        } else {
            addRow(Ui.button(this, s(R.string.wiz_back)) { back() }, next)
            if (between != null) body.addView(between, Ui.lp())
            addRow(cancel)
        }
    }

    // ---- what was answered so far ---------------------------------------------------------

    private fun historyLabel(pg: Int): Int = when (pg) {
        P_SOUND -> R.string.wiz_h_sound
        P_VIB -> R.string.wiz_h_vib
        P_BT -> R.string.wiz_h_bt
        P_MODE, P_LIST -> R.string.wiz_h_allow
        P_AUTO -> R.string.wiz_h_auto
        P_RESTORE -> R.string.wiz_h_restore
        else -> R.string.wiz_h_vol
    }

    private fun historyAnswer(pg: Int): String = when (pg) {
        P_SOUND -> answerText(sound)
        P_VIB -> answerText(vib) + (if (tapVibe) " / " + s(R.string.wiz_tap_vibe) else "")
        P_BT -> if (bt == YES) s(R.string.wiz_bt_yes) + " (" + s(if (btScope == BtMode.EARPHONE_ONLY) R.string.bt_earphone else R.string.bt_all) + ")" else s(R.string.wiz_bt_no)
        P_MODE -> s(if (mode == MODE_NONE) R.string.wiz_none else if (mode == MODE_ALL) R.string.wiz_all else R.string.wiz_custom)
        P_LIST -> allowText()
        P_AUTO -> s(R.string.wiz_auto_on) + ": " + s(if (autoOn) R.string.wiz_do else R.string.wiz_dont) + " / " +
            s(R.string.wiz_auto_off) + ": " + s(if (autoOff) R.string.wiz_do else R.string.wiz_dont)
        P_RESTORE -> s(if (restore) R.string.wiz_restore_yes_short else R.string.wiz_restore_no_short)
        else -> s(when (volMode) { VolMode.RESTORE_SAVED -> R.string.vol_saved; VolMode.FIXED -> R.string.wiz_vol_pick; else -> R.string.vol_off })
    }

    /** Small and faint, centred under the buttons: the oldest question at the bottom, an arrow up between two of them. */
    private fun addHistory() {
        val lines = ArrayList<String>()
        for (pg in history) lines.add("Q" + (questionPages().indexOf(pg) + 1) + " " + s(historyLabel(pg)) + ": " + historyAnswer(pg))
        if (lines.isEmpty()) return
        val box = Ui.vbox(this).apply { alpha = 0.6f; gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, dp(GAP_DP * 3), 0, 0) }
        box.addView(Ui.text(this, s(R.string.wiz_history), 11f, bold = true, secondary = true).apply { gravity = Gravity.CENTER })
        lines.asReversed().forEachIndexed { i, l ->
            if (i > 0) box.addView(Ui.text(this, "↑", 11f, secondary = true).apply { gravity = Gravity.CENTER })
            box.addView(Ui.text(this, l, 11f, secondary = true).apply { gravity = Gravity.CENTER })
        }
        body.addView(box, Ui.lp())
    }

    private fun answerText(a: Int): String = when (a) { YES -> s(R.string.wiz_yes); NO -> s(R.string.wiz_no); else -> s(R.string.wiz_depends) }

    private fun allowText(): String {
        if (nothingChecked()) return s(R.string.wiz_h_apps_only)
        val l = ArrayList<String>()
        if (callsOn) l.add(s(R.string.allow_calls) + "(" + Describe.senders(this, callsWho) + ")")
        if (msgsOn) l.add(s(R.string.allow_messages) + "(" + Describe.senders(this, msgsWho) + ")")
        if (media) l.add(s(R.string.allow_media))
        if (alarms) l.add(s(R.string.allow_alarms))
        if (system) l.add(s(R.string.allow_system))
        if (rem) l.add(s(R.string.allow_reminders))
        if (evt) l.add(s(R.string.allow_events))
        return l.joinToString(", ")
    }

    private fun render() {
        body.removeAllViews()
        stepText.text = if (page == P_DONE) s(R.string.wiz_done_head) else {
            val q = questionPages()
            s(R.string.wiz_qhead, q.indexOf(page) + 1, q.size)
        }
        when (page) {
            P_SOUND -> renderSound()
            P_VIB -> renderVib()
            P_BT -> renderBt()
            P_MODE -> renderMode()
            P_LIST -> renderList()
            P_AUTO -> renderAuto()
            P_RESTORE -> renderRestore()
            P_VOL -> renderVol()
            else -> renderDone()
        }
        addHistory()
    }

    // ---- 1 / 2: ringer and vibration --------------------------------------------------------

    private fun renderSound() {
        title(s(R.string.wiz_q_sound))
        answers3(sound) { a -> sound = a; render() }
        nav(sound != UNSET) { go(after(P_SOUND)) }
        body.addView(Ui.button(this, s(R.string.wiz_skip)) {
            startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("tab", 0))
            finish()
        }, Ui.lp().apply { topMargin = dp(GAP_DP) })
    }

    private fun renderVib() {
        title(s(R.string.wiz_q_vib))
        answers3(vib) { a -> vib = a; render() }
        body.addView(CheckBox(this).apply {
            text = s(R.string.wiz_tap_vibe); isChecked = tapVibe
            setOnCheckedChangeListener { _, v -> tapVibe = v; render() }
        }, Ui.lp().apply { topMargin = dp(GAP_DP * 2) })
        val needSetting = vib == YES || vib == DEPENDS || tapVibe
        val between: View? = if (!needSetting) null else Ui.vbox(this).apply {
            addView(small(s(R.string.wiz_vib_need)), Ui.lp())
            addView(Ui.button(this@WizardActivity, s(R.string.wiz_open_settings)) { openVibrationSettings() }, Ui.lp().apply { topMargin = dp(GAP_DP) })
        }
        if (between != null) body.addView(between, Ui.lp())
        nav(vib != UNSET) { go(after(P_VIB)) }
    }

    /** The phone's sound settings (the "Vibration & haptics" entry is in there). This wizard stays open underneath. */
    private fun openVibrationSettings() {
        try { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) } catch (e: Exception) { Ui.toast(this, s(R.string.wiz_settings_failed), false) }
    }

    // ---- 3: Bluetooth audio -------------------------------------------------------------------

    private fun renderBt() {
        title(s(R.string.wiz_q_bt))
        yesNo(bt, R.string.wiz_bt_yes, R.string.wiz_bt_no) { a -> bt = a; render() }
        body.addView(label(s(R.string.wiz_q_bt_which)), Ui.lp())
        val on = bt == YES // greyed until "yes" is chosen
        addRow(
            choice(s(R.string.bt_all), on && btScope == BtMode.ALL_AUDIO, on) { btScope = BtMode.ALL_AUDIO; render() },
            choice(s(R.string.bt_earphone), on && btScope == BtMode.EARPHONE_ONLY, on) { btScope = BtMode.EARPHONE_ONLY; render() },
        )
        nav(bt == NO || (bt == YES && btScope != UNSET)) { go(after(P_BT)) }
    }

    // ---- 4 / 5: which sounds may still be heard -----------------------------------------------------

    private fun renderMode() {
        title(s(R.string.wiz_q_allow))
        addRow(choice(s(R.string.wiz_none), mode == MODE_NONE) { mode = MODE_NONE; render() }, choice(s(R.string.wiz_all), mode == MODE_ALL) { mode = MODE_ALL; render() })
        addRow(choice(s(R.string.wiz_custom), mode == MODE_CUSTOM) { mode = MODE_CUSTOM; render() })
        nav(mode != UNSET) { go(after(P_MODE)) }
    }

    private fun renderList() {
        title(s(R.string.wiz_q_list))
        body.addView(small(s(R.string.wiz_multi)), Ui.lp())
        val muted = mutedOnly()
        val list = Ui.vbox(this)
        list.addView(sendersRow(s(R.string.wiz_calls), callsOn, callsWho, { callsOn = it }, { callsWho = it }, muted))
        list.addView(sendersRow(s(R.string.allow_messages), msgsOn, msgsWho, { msgsOn = it }, { msgsWho = it }, muted))
        fun check(text: String, get: () -> Boolean, set: (Boolean) -> Unit): CheckBox = CheckBox(this).apply {
            this.text = text; isChecked = get(); setOnCheckedChangeListener { _, v -> set(v) }
        }
        list.addView(check(s(R.string.allow_media), { media }, { media = it }))
        list.addView(check(s(R.string.allow_alarms), { alarms }, { alarms = it }))
        list.addView(check(s(R.string.allow_system), { system }, { system = it }))
        list.addView(check(s(R.string.allow_reminders), { rem }, { rem = it }))
        list.addView(check(s(R.string.allow_events), { evt }, { evt = it }))
        // apps: not a per-profile setting
        list.addView(Ui.text(this, s(R.string.wiz_row_apps_only), 15f, bold = true).apply { setPadding(0, dp(GAP_DP), 0, 0) })
        list.addView(small(s(R.string.wiz_apps_only_note)).apply { gravity = Gravity.START })
        list.addView(Ui.button(this, s(R.string.apps_open_btn)) { Ui.openPrioritySettings(this) }, Ui.lp().apply { topMargin = dp(GAP_DP) })
        body.addView(list, Ui.lp().apply { topMargin = dp(GAP_DP) })
        val foot = small(s(R.string.wiz_list_foot)).apply { textSize = 11f; setTextColor(getColor(R.color.accent)) }
        nav(true, between = foot) { go(after(P_LIST)) }
    }

    /** A check box with who may reach through. Greyed (and asking to change answer 1 / 2 when touched) if nothing could alert. */
    private fun sendersRow(text: String, on: Boolean, who: Int, setOn: (Boolean) -> Unit, setWho: (Int) -> Unit, muted: Boolean): View {
        val box = Ui.vbox(this)
        box.addView(CheckBox(this).apply { this.text = text; isChecked = on && !muted; setOnCheckedChangeListener { _, v -> setOn(v); render() } })
        if (on && !muted) {
            val senders = listOf(1, 2, 3)
            val row = rowOf(*senders.map { sd -> choice(Describe.senders(this, sd), who == sd) { setWho(sd); render() } }.toTypedArray())
            box.addView(row, Ui.lp().apply { marginStart = dp(32); topMargin = dp(4); bottomMargin = dp(4) })
        }
        if (!muted) return box
        grey(box, true)
        return FrameLayout(this).apply {
            addView(box)
            addView(View(this@WizardActivity).apply {
                isClickable = true
                setOnClickListener {
                    AlertDialog.Builder(this@WizardActivity)
                        .setMessage(s(R.string.wiz_muted_ask))
                        .setPositiveButton(s(R.string.wiz_muted_change)) { _, _ -> jumpTo(P_VIB) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    // ---- 6 / 7 / 8: timers, what to put back ----------------------------------------------------

    private fun renderAuto() {
        title(s(R.string.wiz_q_auto))
        body.addView(label(s(R.string.wiz_auto_on)), Ui.lp())
        addRow(choice(s(R.string.wiz_do), autoOn) { autoOn = true; render() }, choice(s(R.string.wiz_dont), !autoOn) { autoOn = false; render() })
        body.addView(label(s(R.string.wiz_auto_off)), Ui.lp())
        addRow(choice(s(R.string.wiz_do), autoOff) { autoOff = true; render() }, choice(s(R.string.wiz_dont), !autoOff) { autoOff = false; render() })
        nav(true) { go(after(P_AUTO)) }
    }

    private fun renderRestore() {
        title(s(R.string.wiz_q_restore))
        addRow(choice(s(R.string.wiz_restore_yes), restore) { restore = true; render() }, choice(s(R.string.wiz_restore_no), !restore) { restore = false; render() })
        nav(true) { go(after(P_RESTORE)) }
    }

    private fun renderVol() {
        title(s(R.string.wiz_q_vol))
        addRow(
            choice(s(R.string.wiz_vol_keep), volMode == VolMode.OFF) { volMode = VolMode.OFF; render() },
            choice(s(R.string.wiz_vol_back), volMode == VolMode.RESTORE_SAVED) { volMode = VolMode.RESTORE_SAVED; render() },
        )
        addRow(choice(s(R.string.wiz_vol_pick), volMode == VolMode.FIXED) { pickVolumes() })
        if (volMode == VolMode.FIXED) {
            val names = listOf(R.string.vol_ring, R.string.vol_notification, R.string.vol_media, R.string.vol_alarm)
            body.addView(small(names.indices.joinToString("  ") { s(names[it]) + " " + volFixed[it] + "%" }), Ui.lp())
        }
        nav(true) { go(after(P_VOL)) }
    }

    /** Four sliders (ring, notification, media, alarm). Cancel keeps the earlier choice. */
    private fun pickVolumes() {
        val vals = volFixed.toMutableList()
        val box = Ui.vbox(this).apply { setPadding(dp(20), dp(8), dp(20), 0) }
        listOf(R.string.vol_ring, R.string.vol_notification, R.string.vol_media, R.string.vol_alarm).forEachIndexed { idx, res ->
            val text = Ui.text(this, s(res) + ": " + vals[idx] + "%", 14f)
            box.addView(text)
            box.addView(SeekBar(this).apply {
                max = 100
                progress = vals[idx]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                        vals[idx] = value
                        text.text = s(res) + ": " + value + "%"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }
        AlertDialog.Builder(this)
            .setTitle(s(R.string.wiz_vol_pick))
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> volFixed = vals; volMode = VolMode.FIXED; render() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- result ---------------------------------------------------------------------------------

    private class Made(val p: Profile, val patIdx: Int)

    /** The profiles the answers lead to (one per ringer case). Two answers that lead to the same behaviour give one profile. */
    private fun buildProfiles(): List<Made> {
        val useBt = bt == YES
        val out = ArrayList<Made>()
        fun add(p: Profile, patIdx: Int) {
            if (out.none { behaviorKey(it.p) == behaviorKey(p) }) out.add(Made(p, patIdx))
        }
        fun prep(p: Profile): Profile {
            p.duration = if (autoOff) Dur.ASK_TIME else Dur.NONE
            p.schedOn = autoOn
            p.releaseAll = !restore
            p.volMode = volMode
            p.volFixed = volFixed
            p.tapVibe = tapVibe
            p.tab = 0
            return p
        }
        fun blank(r: Int, d: Int): Profile = Profile(Store.newId(), "", r, d, calls = 0, messages = 0).also { it.mediaOnBt = false }

        if (mode == MODE_ALL) {
            for (r in ringers()) add(prep(blank(r, Dnd.OFF).also { it.calls = 1; it.messages = 1 }), Patterns.find(r, Dnd.OFF, Patterns.NO_BT, false))
            return out
        }
        for (r in ringers()) {
            val p = blank(r, Dnd.PRIORITY)
            if (mode == MODE_CUSTOM) {
                p.calls = if (callsOn && r != Ringer.MUTE) callsWho else 0   // a muted phone cannot alert for them
                p.messages = if (msgsOn && r != Ringer.MUTE) msgsWho else 0
                p.media = media; p.alarms = alarms; p.system = system; p.reminders = rem; p.events = evt
            }
            p.mediaOnBt = useBt && !p.media
            p.btScope = if (btScope == UNSET) BtMode.ALL_AUDIO else btScope
            // Only the explicit "None" blocks everything. Nothing ticked in the list keeps the filter and the ringer, because
            // the apps chosen in the phone settings ("only certain apps") can still get through it.
            val nothing = mode == MODE_NONE
            if (nothing) {
                // nothing but (maybe) media while Bluetooth audio is connected: the ringer does not matter any more
                p.ringer = Ringer.MUTE
                if (p.mediaOnBt) add(prep(p), Patterns.find(Ringer.MUTE, Dnd.PRIORITY, p.btScope, true))
                else { p.dnd = Dnd.NONE; add(prep(p), Patterns.find(Ringer.MUTE, Dnd.NONE, Patterns.NO_BT, false)) }
            } else {
                add(prep(p), Patterns.find(r, Dnd.PRIORITY, if (p.mediaOnBt) p.btScope else Patterns.NO_BT, false))
            }
        }
        // Several profiles at once: their timers would all start with the same unseen default time, so none is switched on here
        if (out.size > 1) out.forEach { it.p.schedOn = false }
        return out
    }

    /** What the phone does: equal keys = the same profile (name, look and timer are not part of it). */
    private fun behaviorKey(p: Profile): String = when (p.dnd) {
        Dnd.OFF -> "off:${p.ringer}"
        Dnd.NONE -> "none"
        else -> "pri:${p.ringer}:${p.calls}:${p.messages}:${p.media}:${p.alarms}:${p.system}:${p.reminders}:${p.events}:" +
            (if (p.mediaOnBt && !p.media) "bt${p.btScope}" else "-")
    }

    /** Emojis that tell apart two profiles of the same behaviour name: what the filter lets through, Bluetooth, timers. */
    private fun emojiTail(p: Profile): String {
        val sb = StringBuilder()
        if (p.dnd == Dnd.PRIORITY) {
            if (p.calls > 0) sb.append("📞")
            if (p.messages > 0) sb.append("💬")
            if (p.media) sb.append("🎵")
            if (p.alarms) sb.append("⏰")
            if (p.system) sb.append("🔧")
            if (p.reminders) sb.append("🔔")
            if (p.events) sb.append("📅")
            if (p.mediaOnBt && !p.media) sb.append("🎧")
        }
        if (p.schedOn) sb.append("🕐")
        if (p.duration != Dur.NONE) sb.append("⏱")
        return sb.toString()
    }

    private fun renderDone() {
        body.addView(label(s(R.string.wiz_profiles)), Ui.lp())
        body.addView(small(s(R.string.wiz_pick_hint)), Ui.lp())
        val made = buildProfiles()
        // same sound behaviour AND same auto release time = the same profile (name, look and schedule do not count)
        val existing = Store.profiles(this).filter { it.tab == 0 }.map { behaviorKey(it) + "|" + it.duration }.toSet()
        val fresh = made.filter { behaviorKey(it.p) + "|" + it.p.duration !in existing }
        if (!pickedInit) {
            pickedInit = true
            picked.clear()
            made.forEachIndexed { i, m -> if (m in fresh) picked.add(i) }
        }
        val taken = HashSet(Store.profiles(this).map { it.name })
        made.forEachIndexed { i, m ->
            val isNew = m in fresh
            val selected = isNew && i in picked
            m.p.iconKey = if (m.patIdx >= 0) Patterns.LIST[m.patIdx].icon else "bell_off"
            val base = if (m.patIdx >= 0) s(Patterns.NAMES[m.patIdx]) else s(R.string.wiz_name_default)
            val tail = emojiTail(m.p)
            var name = customNames[i] ?: (if (tail.isEmpty()) base else "$base $tail")
            if (isNew && customNames[i] == null) { // a different profile with the same name: number it
                var n = 2; val first = name
                while (name in taken) { name = "$first $n"; n++ }
                taken.add(name)
            }
            m.p.name = name
            val box = Ui.card(this, active = selected).apply { gravity = Gravity.CENTER_HORIZONTAL }
            box.addView(Widgets.preview(this, m.p, false, 48), Ui.lp(dp(48), dp(48)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            box.addView(Ui.text(this, name, 15f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }, Ui.lp())
            box.addView(Ui.chipsView(this, m.p, centered = true), Ui.lp())
            box.addView(Ui.text(this, Describe.summary(this, m.p), 12f, secondary = true).apply { gravity = Gravity.CENTER }, Ui.lp())
            if (!isNew) {
                box.addView(Ui.text(this, s(R.string.wiz_exists), 12f, bold = true).apply { gravity = Gravity.CENTER }, Ui.lp())
                box.alpha = 0.6f
            } else {
                box.isClickable = true
                box.setOnClickListener { if (i in picked) picked.remove(i) else picked.add(i); render() }
            }
            box.setOnLongClickListener { rename(i, name); true }
            body.addView(box, Ui.lp().apply { topMargin = dp(GAP_DP) })
        }
        // short notes about answers that did not matter
        val notes = ArrayList<String>()
        if (bt == YES && mode == MODE_ALL) notes.add(s(R.string.wiz_note_bt_all))
        if (bt == YES && mode == MODE_CUSTOM && media) notes.add(s(R.string.wiz_note_bt_media))
        if (mode == MODE_CUSTOM && (callsOn || msgsOn) && Ringer.MUTE in ringers() && ringers().size > 1) notes.add(s(R.string.wiz_note_mute_calls))
        if (autoOn && made.size > 1) notes.add(s(R.string.wiz_note_timer))
        for (n in notes) body.addView(small("※ $n"), Ui.lp())
        body.addView(Ui.text(this, s(R.string.legend), 11f, secondary = true).apply { setPadding(0, dp(GAP_DP), 0, 0) }, Ui.lp())
        val chosen = made.indices.filter { it in picked && made[it] in fresh }.map { made[it].p }
        if (fresh.isEmpty()) {
            nav(true, s(R.string.wiz_close)) { finish() }
        } else {
            nav(chosen.isNotEmpty(), s(if (made.size == 1) R.string.wiz_create_one else R.string.wiz_create_sel)) { create(chosen) }
        }
    }

    /** Long press on a result: a new name for that profile. */
    private fun rename(i: Int, current: String) {
        val input = EditText(this).apply { setText(current); setSelectAllOnFocus(true); setSingleLine() }
        val holder = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(s(R.string.wiz_rename))
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { customNames[i] = t; render() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun create(list: List<Profile>) {
        for (p in list) Store.upsert(this, p)
        Widgets.updateAll(this)
        Schedules.reschedule(this)
        try { getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", 0).apply() } catch (e: Exception) { }
        if (list.size == 1) startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("id", list[0].id))
        else Ui.toast(this, s(R.string.wiz_created_n, list.size), false)
        finish()
    }
}
