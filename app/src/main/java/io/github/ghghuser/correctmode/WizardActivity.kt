package io.github.ghghuser.correctmode

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView

/**
 * "Mode finder": a few questions instead of knowing what Ringer / DND mean. A card floating over the main screen.
 *
 *  1. Ring / notification sound wanted?  (yes / no / depends)
 *  2. Vibration wanted?                  (only when the answer to 1 is not "yes": a ringing phone vibrates as the phone
 *                                         settings say, the app cannot change that)
 *  3. Bluetooth audio: listen on the connected device?  (yes: all devices / headsets only)
 *  4. Which sounds may still be heard while quiet?  (none / all / a list; the notification filter is ONE group here)
 *  5. Auto release / timer
 *  6. Result: 1 to 3 profiles ("depends" makes one per case). Profiles that already exist are not made again.
 *
 * It only fills in Profiles; the real work (and the safety checks) happens in Engine when one is turned on.
 * New profiles go to the left tab (your own). One new profile opens in the editor next; several go back to the list.
 */
class WizardActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        const val P_SOUND = 1; const val P_VIB = 2; const val P_BT = 3; const val P_ALLOW = 4; const val P_AUTO = 5; const val P_DONE = 6
        const val YES = 0; const val NO = 1; const val DEPENDS = 2
        const val MODE_CUSTOM = 0; const val MODE_NONE = 1; const val MODE_ALL = 2
        const val UNSET = -1
    }

    // answers
    private var sound = UNSET
    private var vib = UNSET
    private var bt = UNSET // YES / NO
    private var btScope = BtMode.ALL_AUDIO
    private var mode = MODE_CUSTOM
    private var callsOn = false; private var callsWho = 1
    private var msgsOn = false; private var msgsWho = 1
    private var media = false; private var alarms = false; private var system = false
    private var rem = false; private var evt = false
    private var duration = Dur.ASK
    private var timerOn = false

    private val history = ArrayList<Int>()
    private var page = P_SOUND

    private lateinit var body: LinearLayout
    private lateinit var stepText: android.widget.TextView
    private lateinit var navRow: LinearLayout

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT) // fixed size
        val root = Ui.vbox(this).apply { setPadding(dp(28), dp(24), dp(28), dp(20)) }
        root.addView(Ui.text(this, s(R.string.wiz_title), 16f, bold = true).apply { gravity = Gravity.CENTER }, Ui.lp())
        stepText = Ui.text(this, "", 13f, secondary = true).apply { gravity = Gravity.CENTER }
        root.addView(stepText, Ui.lp())
        body = Ui.vbox(this)
        root.addView(ScrollView(this).apply { addView(body) }, Ui.lp(h = 0, weight = 1f).apply { topMargin = dp(4) })
        navRow = Ui.hbox(this).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(navRow)
        setContentView(root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        render()
    }

    private fun back() {
        if (history.isEmpty()) finish() else { page = history.removeAt(history.size - 1); render() }
    }

    private fun go(next: Int) { history.add(page); page = next; render() }

    /** Back to an earlier page (the answers stay). */
    private fun jumpTo(target: Int) {
        while (history.isNotEmpty() && page != target) page = history.removeAt(history.size - 1)
        render()
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

    // ---- building blocks ------------------------------------------------------------------

    private fun title(text: String, hint: String?) {
        body.addView(Ui.text(this, text, 20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(4)) }, Ui.lp())
        if (hint != null) body.addView(Ui.text(this, hint, 12f, secondary = true).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }, Ui.lp())
    }

    private fun small(text: String, topDp: Int = 0): android.widget.TextView =
        Ui.text(this, text, 12f, secondary = true).apply { setPadding(dp(4), dp(topDp), dp(4), 0) }

    private fun nav(nextLabel: String? = null, onNext: (() -> Unit)? = null) {
        navRow.removeAllViews()
        navRow.addView(Ui.button(this, s(R.string.wiz_cancel)) { finish() }, Ui.lp(0, weight = 1f))
        if (history.isNotEmpty()) navRow.addView(Ui.button(this, s(R.string.wiz_back)) { back() }, Ui.lp(0, weight = 1f))
        if (nextLabel != null && onNext != null) navRow.addView(Ui.button(this, nextLabel) { onNext() }, Ui.lp(0, weight = 1f))
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
        if (!on) v.isClickable = false
    }

    private fun grey(v: View, off: Boolean) { v.alpha = if (off) 0.4f else 1f; setEnabledDeep(v, !off) }

    /** Three answers: two side by side, "depends" under them. The chosen one is ticked (when coming back to the page). */
    private fun answers3(current: Int, onPick: (Int) -> Unit) {
        fun label(i: Int, id: Int) = (if (current == i) "✔ " else "") + s(id)
        val row = Ui.hbox(this)
        row.addView(Ui.button(this, label(YES, R.string.wiz_yes)) { onPick(YES) }, Ui.lp(0, weight = 1f))
        row.addView(Ui.button(this, label(NO, R.string.wiz_no)) { onPick(NO) }, Ui.lp(0, weight = 1f))
        body.addView(row, Ui.lp())
        body.addView(Ui.button(this, label(DEPENDS, R.string.wiz_depends)) { onPick(DEPENDS) }, Ui.lp())
    }

    /** What was answered so far: small, faint, the newest right under the question, the oldest at the bottom. */
    private fun addHistory() {
        val lines = ArrayList<String>()
        for (pg in history) {
            when (pg) {
                P_SOUND -> lines.add(s(R.string.wiz_h_sound) + ": " + answerText(sound))
                P_VIB -> lines.add(s(R.string.wiz_h_vib) + ": " + answerText(vib))
                P_BT -> lines.add(s(R.string.wiz_h_bt) + ": " + (if (bt == YES) s(R.string.wiz_yes) + " (" + s(if (btScope == BtMode.EARPHONE_ONLY) R.string.bt_earphone else R.string.bt_all) + ")" else s(R.string.wiz_no)))
                P_ALLOW -> lines.add(s(R.string.wiz_h_allow) + ": " + allowText())
                P_AUTO -> lines.add(s(R.string.wiz_h_auto) + ": " + Ui.durationLabel(this, duration))
            }
        }
        if (lines.isEmpty()) return
        val box = Ui.vbox(this).apply { alpha = 0.6f; setPadding(0, dp(16), 0, 0) }
        for (l in lines.asReversed()) box.addView(Ui.text(this, l, 11f, secondary = true))
        body.addView(box, Ui.lp())
    }

    private fun answerText(a: Int): String = when (a) { YES -> s(R.string.wiz_yes); NO -> s(R.string.wiz_no); else -> s(R.string.wiz_depends) }

    private fun allowText(): String {
        if (mode == MODE_ALL) return s(R.string.wiz_all)
        if (mode == MODE_NONE || nothingChecked()) return s(R.string.wiz_none)
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
        stepText.text = s(R.string.wiz_step1, history.size + 1)
        when (page) {
            P_SOUND -> renderSound()
            P_VIB -> renderVib()
            P_BT -> renderBt()
            P_ALLOW -> renderAllow()
            P_AUTO -> renderAuto()
            else -> renderDone()
        }
    }

    // ---- 1 / 2: ringer and vibration --------------------------------------------------------

    private fun renderSound() {
        title(s(R.string.wiz_q_sound), s(R.string.wiz_q_sound_hint))
        answers3(sound) { a -> sound = a; go(if (a == YES) P_BT else P_VIB) }
        nav()
        body.addView(Ui.button(this, s(R.string.wiz_skip)) {
            startActivity(Intent(this, ProfileEditActivity::class.java).putExtra("tab", 0))
            finish()
        }, Ui.lp().apply { topMargin = dp(12) })
        addHistory()
    }

    private fun renderVib() {
        title(s(R.string.wiz_q_vib), s(R.string.wiz_q_vib_hint))
        answers3(vib) { a -> vib = a; go(P_BT) }
        nav()
        addHistory()
    }

    // ---- 3: Bluetooth audio -------------------------------------------------------------------

    private fun renderBt() {
        title(s(R.string.wiz_q_bt), s(R.string.wiz_q_bt_hint))
        body.addView(Ui.boxChoice(this, listOf("🎧" to s(R.string.wiz_yes), "🔇" to s(R.string.wiz_no)), if (bt == YES) 0 else if (bt == NO) 1 else -1) { i ->
            bt = if (i == 0) YES else NO
            render()
        }, Ui.lp())
        body.addView(Ui.text(this, s(R.string.wiz_q_bt_which), 14f, bold = true).apply { setPadding(0, dp(16), 0, dp(4)) }, Ui.lp())
        val scope = Ui.boxChoice(this, listOf("🎧" to s(R.string.bt_all), "🎙" to s(R.string.bt_earphone)), btScope) { i -> btScope = i; render() }
        grey(scope, bt != YES) // until "yes" is chosen
        body.addView(scope, Ui.lp())
        nav(s(R.string.wiz_next)) {
            if (bt == UNSET) Ui.toast(this, s(R.string.wiz_choose_first), false) else go(P_ALLOW)
        }
        addHistory()
    }

    // ---- 4: which sounds may still be heard -----------------------------------------------------

    private fun renderAllow() {
        title(s(R.string.wiz_q_allow), s(R.string.wiz_q_allow_hint))
        val list = Ui.vbox(this)
        body.addView(Ui.boxChoice(this, listOf("🚫" to s(R.string.wiz_none), "📢" to s(R.string.wiz_all)), if (mode == MODE_NONE) 0 else if (mode == MODE_ALL) 1 else -1) { i ->
            val target = if (i == 0) MODE_NONE else MODE_ALL
            mode = if (mode == target) MODE_CUSTOM else target // tap again = back to choosing one by one
            render()
        }, Ui.lp())
        if (mode == MODE_ALL) body.addView(small(s(R.string.wiz_all_note), 4), Ui.lp())

        val muted = mutedOnly()
        list.addView(sendersRow("📞 " + s(R.string.wiz_calls), callsOn, callsWho, { callsOn = it }, { callsWho = it }, muted))
        list.addView(sendersRow("💬 " + s(R.string.allow_messages), msgsOn, msgsWho, { msgsOn = it }, { msgsWho = it }, muted))
        fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit): CheckBox = CheckBox(this).apply {
            text = label; isChecked = get(); setOnCheckedChangeListener { _, v -> set(v) }
        }
        list.addView(check("🎵 " + s(R.string.allow_media), { media }, { media = it }))
        list.addView(check("⏰ " + s(R.string.allow_alarms), { alarms }, { alarms = it }))
        list.addView(check("🔧 " + s(R.string.allow_system), { system }, { system = it }))
        list.addView(check("🔔 " + s(R.string.allow_reminders), { rem }, { rem = it }))
        list.addView(check("📅 " + s(R.string.allow_events), { evt }, { evt = it }))
        // apps: not a per-profile setting
        list.addView(Ui.text(this, "📲 " + s(R.string.wiz_row_apps_only), 15f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
        list.addView(small(s(R.string.wiz_apps_only_note)))
        list.addView(Ui.button(this, s(R.string.apps_open_btn)) { Ui.openPrioritySettings(this) })
        grey(list, mode != MODE_CUSTOM)
        body.addView(list, Ui.lp().apply { topMargin = dp(8) })
        nav(s(R.string.wiz_next)) { go(P_AUTO) }
        addHistory()
    }

    /** A check box with who may reach through. Greyed (and asking to change answer 1 / 2 when touched) if nothing could alert. */
    private fun sendersRow(label: String, on: Boolean, who: Int, setOn: (Boolean) -> Unit, setWho: (Int) -> Unit, muted: Boolean): View {
        val box = Ui.vbox(this)
        box.addView(CheckBox(this).apply { text = label; isChecked = on && !muted; setOnCheckedChangeListener { _, v -> setOn(v); render() } })
        if (on && !muted) {
            val senders = listOf(1, 2, 3)
            box.addView(Ui.boxChoice(this, senders.map { "" to Describe.senders(this, it) }, who - 1) { i -> setWho(senders[i]); render() }.apply { setPadding(dp(32), 0, 0, 0) })
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

    // ---- 5: auto release ----------------------------------------------------------------------

    private fun renderAuto() {
        title(s(R.string.wiz_q6), s(R.string.wiz_q6_hint))
        val g = RadioGroup(this)
        listOf(Dur.ASK, 30, 60, 120, Dur.NONE).forEach { d ->
            g.addView(RadioButton(this).apply {
                text = Ui.durationLabel(this@WizardActivity, d); id = View.generateViewId(); isChecked = duration == d
                setOnClickListener { duration = d }
            })
        }
        body.addView(g)
        body.addView(CheckBox(this).apply {
            text = s(R.string.wiz_timer_check); isChecked = timerOn
            setOnCheckedChangeListener { _, v -> timerOn = v }
        }, Ui.lp().apply { topMargin = dp(8) })
        body.addView(small(s(R.string.wiz_timer_hint)), Ui.lp())
        nav(s(R.string.wiz_next)) { go(P_DONE) }
        addHistory()
    }

    // ---- result ---------------------------------------------------------------------------------

    private class Made(val p: Profile, val patIdx: Int)

    /** The profiles the answers lead to (1 to 3). Two answers that lead to the same behaviour give one profile. */
    private fun buildProfiles(): List<Made> {
        val useBt = bt == YES
        val out = ArrayList<Made>()
        fun add(p: Profile, patIdx: Int) {
            if (out.none { behaviorKey(it.p) == behaviorKey(p) }) out.add(Made(p, patIdx))
        }
        fun prep(p: Profile): Profile { p.duration = duration; p.schedOn = timerOn; p.tab = 0; return p }
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
            p.btScope = btScope
            val nothing = p.calls == 0 && p.messages == 0 && !p.media && !p.alarms && !p.system && !p.reminders && !p.events
            if (nothing) {
                // nothing but (maybe) media while Bluetooth audio is connected: the ringer does not matter any more
                p.ringer = Ringer.MUTE
                if (p.mediaOnBt) add(prep(p), Patterns.find(Ringer.MUTE, Dnd.PRIORITY, btScope, true))
                else { p.dnd = Dnd.NONE; add(prep(p), Patterns.find(Ringer.MUTE, Dnd.NONE, Patterns.NO_BT, false)) }
            } else {
                add(prep(p), Patterns.find(r, Dnd.PRIORITY, if (p.mediaOnBt) btScope else Patterns.NO_BT, false))
            }
        }
        return out
    }

    /** What the phone does: equal keys = the same profile (name, look and timer are not part of it). */
    private fun behaviorKey(p: Profile): String = when (p.dnd) {
        Dnd.OFF -> "off:${p.ringer}"
        Dnd.NONE -> "none"
        else -> "pri:${p.ringer}:${p.calls}:${p.messages}:${p.media}:${p.alarms}:${p.system}:${p.reminders}:${p.events}:" +
            (if (p.mediaOnBt && !p.media) "bt${p.btScope}" else "-")
    }

    private fun renderDone() {
        title(s(R.string.wiz_q7), s(R.string.wiz_q7_hint))
        val made = buildProfiles()
        val existing = Store.profiles(this).filter { it.tab == 0 }.map { behaviorKey(it) }.toSet()
        val fresh = made.filter { behaviorKey(it.p) !in existing }
        val names = HashSet(Store.profiles(this).map { it.name })
        for (m in made) {
            val isNew = m in fresh
            val box = Ui.card(this)
            val icon = if (m.patIdx >= 0) Patterns.LIST[m.patIdx].icon else "bell_off"
            m.p.iconKey = icon
            var name = if (m.patIdx >= 0) s(Patterns.NAMES[m.patIdx]) else s(R.string.wiz_name_default)
            if (isNew) { // a different profile with the same name: number it
                var n = 2; val base = name
                while (name in names) { name = "$base $n"; n++ }
                names.add(name)
            }
            m.p.name = name
            val head = Ui.hbox(this)
            head.addView(Widgets.preview(this, m.p, false, 48), Ui.lp(dp(48), dp(48)))
            head.addView(Ui.text(this, name, 15f, bold = true).apply { setPadding(dp(10), 0, 0, 0) }, Ui.lp(0, weight = 1f))
            box.addView(head, Ui.lp())
            box.addView(Ui.chipsView(this, m.p), Ui.lp())
            box.addView(Ui.text(this, Describe.summary(this, m.p), 12f, secondary = true), Ui.lp())
            if (!isNew) box.addView(Ui.text(this, s(R.string.wiz_exists), 12f, bold = true), Ui.lp())
            body.addView(box, Ui.lp().apply { topMargin = dp(8) })
        }
        // short notes about answers that did not matter
        val notes = ArrayList<String>()
        if (bt == YES && mode == MODE_ALL) notes.add(s(R.string.wiz_note_bt_all))
        if (bt == YES && mode == MODE_CUSTOM && media) notes.add(s(R.string.wiz_note_bt_media))
        if (mode == MODE_CUSTOM && (callsOn || msgsOn) && Ringer.MUTE in ringers() && ringers().size > 1) notes.add(s(R.string.wiz_note_mute_calls))
        for (n in notes) body.addView(small("※ $n", 8), Ui.lp())
        body.addView(Ui.text(this, s(R.string.legend), 11f, secondary = true).apply { setPadding(0, dp(8), 0, 0) }, Ui.lp())
        if (fresh.isEmpty()) {
            nav(s(R.string.wiz_close)) { finish() }
        } else {
            nav(s(R.string.wiz_create_n, fresh.size)) { create(fresh.map { it.p }) }
        }
        addHistory()
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
