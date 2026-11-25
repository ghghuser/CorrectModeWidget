package io.github.ghghuser.correctmode

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ImageView
import android.widget.ScrollView
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Create / edit one Ringer + DND combination and the look of its widget button. */
class ProfileEditActivity : Activity(), BackGuard {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        const val REQ_IMG = 10
        // a short list: any emoji can be typed below, and any picture can be chosen
        val EMOJIS = listOf("🤫", "📳", "🔕", "🔔", "🌙", "🎧", "🚗", "🏢", "📚", "🎬", "🏠", "💼")
        val PALETTE = listOf(
            0xFF1F6FEBL, 0xFF0F9D8AL, 0xFF2E7D32L, 0xFFF9A825L, 0xFFEF6C00L, 0xFFD32F2FL, 0xFFC2185BL,
            0xFF7B1FA2L, 0xFF455A64L, 0xFF000000L, 0xFFFFFFFFL, 0xFFE3E6EAL, 0xFF30343AL, 0xFF8AB4FFL,
        ).map { it.toInt() }
        val START_DELAYS = listOf(Dur.NONE, Dur.ASK, 30, 60, 120, Dur.ASK_TIME, Dur.ASK_DATETIME)
        val DURATIONS = listOf(Dur.ASK, Dur.ASK_TIME, Dur.ASK_DATETIME, Dur.NONE, 15, 30, 60, 120, 180, 240)
    }

    private lateinit var p: Profile
    private var isNew = false
    private var origImage: String? = null
    private var origImageOn: String? = null
    private val created = ArrayList<String>()
    private var saved = false
    private lateinit var initialJson: String

    private lateinit var previewBox: LinearLayout
    private lateinit var detailBox: LinearLayout
    private lateinit var forcedNote: TextView
    private lateinit var effectBox: LinearLayout
    private lateinit var btCheck: CheckBox
    private lateinit var btScopeBtn: android.widget.Button
    private lateinit var callsBtn: android.widget.Button
    private lateinit var msgBtn: android.widget.Button
    private lateinit var emojiEdit: EditText
    /** true = the icon grid / emoji field edit the icon used while the profile is ON (only with p.onSep) */
    private var editOn = false
    /** the on-state icon has been given a first value (set once, kept when the checkbox is toggled) */
    private var onInit = false
    private lateinit var iconBox: GridLayout
    private lateinit var lookCard: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var choiceBox: LinearLayout
    private lateinit var schedBox: LinearLayout

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tStart = android.os.SystemClock.elapsedRealtime()
        val ex = Store.profile(this, intent?.getStringExtra("id"))
        isNew = ex == null
        val fromPattern = intent?.getIntExtra("pattern", -1) ?: -1
        p = ex?.copy() ?: if (fromPattern in Patterns.LIST.indices) Store.freshPreset(this, fromPattern, Store.newId(), 0, -1)
        else Profile(Store.newId(), s(R.string.new_profile), tab = (intent?.getIntExtra("tab", 0) ?: 0).coerceIn(0, 1))
        initialJson = p.toJson().toString()
        origImage = ex?.imageFile
        origImageOn = ex?.imageFileOn
        title = s(if (isNew) R.string.profile_new_title else R.string.profile_edit_title)

        val root = Ui.vbox(this).apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        buildNameAndPreview(root)
        buildBehavior(root)
        buildSchedule(root)
        buildLook(root)
        buildButtons(root)
        // the check mark at the top right saves (the "Save" button at the bottom does the same)
        val check = Ui.headerButton(this, "✓", s(R.string.save)) { save() }
        val screen = Ui.screen(this, title.toString(), true, root, listOf(check))
        // the preview stays in view while the colours / icon are being set: it is not part of the scrolling body
        val bar = Ui.vbox(this).apply {
            setPadding(dp(16), dp(2), dp(16), dp(2))
            addView(previewBox, Ui.lp())
            addView(Ui.text(this@ProfileEditActivity, s(R.string.preview_hint), 11f, secondary = true).apply { gravity = Gravity.CENTER }, Ui.lp())
        }
        (screen as LinearLayout).addView(bar, 1, Ui.lp())
        scroll = screen.getChildAt(2) as ScrollView
        setContentView(screen)
        refreshBehavior()
        refreshLook()
        // diagnostics only (Settings): from the start of onCreate to just before the first frame is drawn (rough)
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                Store.setPerf(this@ProfileEditActivity, "edit", android.os.SystemClock.elapsedRealtime() - tStart)
                return true
            }
        })
        // swipe / back button: ask first when something was changed (target 36 does not call onBackPressed any more)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { leave() }
    }

    private fun dirty(): Boolean = p.toJson().toString() != initialJson

    override fun leave() {
        if (saved || !dirty()) { finish(); return }
        android.app.AlertDialog.Builder(this)
            .setMessage(s(R.string.leave_unsaved))
            .setPositiveButton(s(R.string.save)) { _, _ -> save() }
            .setNegativeButton(s(R.string.discard)) { _, _ -> finish() }
            .setNeutralButton(s(R.string.keep_editing), null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!saved && !isChangingConfigurations) created.forEach { Store.imageFile(this, it).delete() }
    }

    // ---- helpers --------------------------------------------------------------------------

    private fun EditText.onChange(cb: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            override fun afterTextChanged(e: Editable?) { cb(e?.toString() ?: "") }
        })
    }

    private fun updatePreview() {
        fillPreview(previewBox, true)
        if (lookDialog != null) fillPreview(lookPreview, false)
    }

    private fun fillPreview(box: LinearLayout, tapToEdit: Boolean) {
        box.removeAllViews()
        for (on in listOf(false, true)) {
            val col = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), 0, dp(6), 0) }
            col.addView(Widgets.preview(this, p, on, 72, outline = true, margins = Widgets.Margins.OWN))
            // the label sits centred right under its own icon
            col.addView(Ui.text(this, s(if (on) R.string.state_on else R.string.state_off), 11f, secondary = true).apply { gravity = Gravity.CENTER })
            // tap an icon: the look window opens on that state's tab (off = left, on = middle); inside the window the tab changes
            val tab = if (on) 1 else 0
            if (tapToEdit) col.setOnClickListener { openLook(tab) }
            else col.setOnClickListener { if (lookTab != tab) { lookTab = tab; renderLook() } }
            box.addView(col)
        }
    }

    // ---- name + preview -------------------------------------------------------------------

    private fun buildNameAndPreview(root: LinearLayout) {
        previewBox = Ui.hbox(this).apply { gravity = Gravity.CENTER }
        root.addView(Ui.text(this, s(R.string.name), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(EditText(this).apply {
            setText(p.name)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(30))
            setSingleLine()
            onChange { p.name = it; updateMatrix(); updatePreview() }
        })
    }

    // ---- behaviour ------------------------------------------------------------------------

    private fun buildBehavior(root: LinearLayout) {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.behavior_title), 16f, bold = true))

        forcedNote = Ui.text(this, s(R.string.dnd_forced_note), 12f, secondary = true)
        buildPriorityOptions()
        // ringer and DND, each with its options; the options of the notification filter sit right under it
        choiceBox = Ui.vbox(this)
        card.addView(choiceBox)
        buildChoice()

        effectBox = Ui.vbox(this).apply { setPadding(0, dp(8), 0, dp(4)) }
        card.addView(effectBox)

        val startBtn = Ui.button(this, "") {}
        fun setStartText() { startBtn.text = s(R.string.start_label) + ": " + Ui.startLabel(this, p.startDelay) }
        startBtn.setOnClickListener {
            Ui.choose(this, s(R.string.start_label), START_DELAYS.map { Ui.startLabel(this, it) }, START_DELAYS.indexOf(p.startDelay)) { i ->
                p.startDelay = START_DELAYS[i]; setStartText()
            }
        }
        setStartText()
        card.addView(startBtn)

        val durLabels = DURATIONS.map { Ui.durationLabel(this, it) } + s(R.string.duration_free)
        val durBtn = Ui.button(this, "") {}
        fun setDurText() { durBtn.text = s(R.string.duration_label) + ": " + Ui.durationLabel(this, p.duration) }
        durBtn.setOnClickListener {
            Ui.choose(this, s(R.string.duration_label), durLabels, DURATIONS.indexOf(p.duration)) { i ->
                if (i < DURATIONS.size) { p.duration = DURATIONS[i]; setDurText() }
                else Ui.promptText(this, s(R.string.duration_free_title), s(R.string.duration_free_hint), "", 6, numeric = true) { t ->
                    val m = Flow.parseMinutes(t)
                    if (m == null) Ui.toast(this, s(R.string.duration_free_bad), true) else { p.duration = m; setDurText() }
                }
            }
        }
        setDurText()
        card.addView(durBtn)
        card.addView(CheckBox(this).apply {
            text = s(R.string.opt_tap_vibe); isChecked = p.tapVibe
            setOnCheckedChangeListener { _, v -> p.tapVibe = v }
        })

        card.addView(CheckBox(this).apply {
            text = s(R.string.opt_release_all); isChecked = p.releaseAll
            setOnCheckedChangeListener { _, v -> p.releaseAll = v }
        })
        card.addView(Ui.text(this, s(R.string.opt_release_all_hint), 11f, secondary = true))

        // what happens to the volumes when THIS profile is released
        val volBox = Ui.vbox(this)
        fun refreshVol() {
            volBox.removeAllViews()
            val labels = listOf(s(R.string.vol_off), s(R.string.vol_saved), s(R.string.vol_fixed))
            volBox.addView(Ui.button(this, s(R.string.vol_restore) + ": " + labels[p.volMode]) {
                Ui.choose(this, s(R.string.vol_restore), labels, p.volMode) { i -> p.volMode = i; refreshVol() }
            })
            volBox.addView(Ui.text(this, s(R.string.vol_hint), 12f, secondary = true))
            if (p.volMode == VolMode.FIXED) {
                listOf(R.string.vol_ring, R.string.vol_notification, R.string.vol_media, R.string.vol_alarm).forEachIndexed { idx, res ->
                    val label = Ui.text(this, s(res) + ": " + p.volFixed[idx] + "%", 13f)
                    volBox.addView(label)
                    volBox.addView(SeekBar(this).apply {
                        max = 100
                        progress = p.volFixed[idx]
                        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                                label.text = s(res) + ": " + value + "%"
                            }
                            override fun onStartTrackingTouch(sb: SeekBar) {}
                            override fun onStopTrackingTouch(sb: SeekBar) {
                                p.volFixed = p.volFixed.toMutableList().also { it[idx] = sb.progress }
                            }
                        })
                    })
                }
            }
        }
        refreshVol()
        card.addView(volBox)
        root.addView(card)
    }

    // ---- timer: turns the profile on by itself ----------------------------------------------

    private fun pickClock(initial: Int, onPick: (Int) -> Unit) {
        android.app.TimePickerDialog(
            this, { _, h, m -> onPick(h * 60 + m) }, initial / 60, initial % 60,
            android.text.format.DateFormat.is24HourFormat(this),
        ).show()
    }

    private fun buildSchedule(root: LinearLayout) {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.sched_title), 16f, bold = true))
        card.addView(CheckBox(this).apply {
            text = s(R.string.sched_enable); isChecked = p.schedOn
            setOnCheckedChangeListener { _, v -> p.schedOn = v; fillSched() }
        })
        schedBox = Ui.vbox(this)
        card.addView(schedBox)
        root.addView(card)
        fillSched()
    }

    private fun fillSched() {
        schedBox.removeAllViews()
        if (!p.schedOn) return
        schedBox.addView(Ui.button(this, s(R.string.sched_start, Ui.clock(p.schedStart))) {
            pickClock(p.schedStart) { p.schedStart = it; fillSched() }
        })
        schedBox.addView(CheckBox(this).apply {
            text = s(R.string.sched_end_check); isChecked = p.schedEnd >= 0
            setOnCheckedChangeListener { _, v -> p.schedEnd = if (v) (p.schedStart + 8 * 60) % 1440 else -1; fillSched() }
        })
        if (p.schedEnd >= 0) schedBox.addView(Ui.button(this, s(R.string.sched_end, Ui.clock(p.schedEnd))) {
            pickClock(p.schedEnd) { p.schedEnd = it; fillSched() }
        })
        schedBox.addView(Ui.text(this, s(R.string.sched_days), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        val days = Ui.hbox(this)
        val names = s(R.string.sched_day_names).split(',')
        for (i in 0 until 7) {
            val on = (p.schedDays shr i) and 1 != 0
            days.addView(Ui.text(this, names.getOrElse(i) { "?" }, 14f, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    if (on) { setColor(0x331F6FEB); setStroke(dp(2), 0xFF1F6FEB.toInt()) } else setStroke(dp(1), 0x55808080)
                }
                setOnClickListener {
                    val next = p.schedDays xor (1 shl i)
                    if (next != 0) { p.schedDays = next; fillSched() } // at least one day stays selected
                }
            }, Ui.lp(0, weight = 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
        }
        schedBox.addView(days)
        schedBox.addView(Ui.text(this, s(R.string.sched_hint), 11f, secondary = true).apply { setPadding(0, dp(6), 0, 0) })
    }

    private val dndRadios = ArrayList<Pair<Int, RadioButton>>()

    private fun buildChoice() {
        choiceBox.removeAllViews()
        dndRadios.clear()
        choiceBox.addView(Ui.text(this, "🔕 " + s(R.string.ringer_label), 14f, bold = true).apply { setPadding(0, dp(8), 0, dp(4)) })
        val ringers = listOf(Ringer.NORMAL to "🔔", Ringer.VIBRATE to "📳", Ringer.MUTE to "🔕")
        choiceBox.addView(Ui.boxChoice(this, ringers.map { it.second to Describe.ringer(this, it.first) }, ringers.indexOfFirst { it.first == p.ringer }) { i ->
            p.ringer = ringers[i].first
            buildChoice(); refreshBehavior()
        })

        choiceBox.addView(Ui.text(this, "🌙 " + s(R.string.dnd_heading), 14f, bold = true).apply { setPadding(0, dp(12), 0, 0) })
        val opts = listOf(Dnd.OFF to "📢", Dnd.NONE to "🚫", Dnd.PRIORITY to "🔉")
        for ((d, emoji) in opts) {
            val rb = RadioButton(this).apply {
                text = emoji + " " + Describe.dnd(this@ProfileEditActivity, d)
                isChecked = p.dnd == d
                setOnClickListener { p.dnd = d; refreshBehavior() }
            }
            dndRadios.add(d to rb)
            choiceBox.addView(rb)
            if (d == Dnd.PRIORITY) choiceBox.addView(detailBox)
        }
        choiceBox.addView(forcedNote)
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    private fun buildPriorityOptions() {
        detailBox = Ui.vbox(this).apply { setPadding(dp(32), 0, 0, dp(4)) }
        val senders = listOf(0, 1, 2, 3).map { Describe.senders(this, it) }
        callsBtn = Ui.button(this, "") {
            Ui.choose(this, s(R.string.allow_calls), senders, p.calls) { i -> p.calls = i; refreshBehavior() }
        }
        msgBtn = Ui.button(this, "") {
            Ui.choose(this, s(R.string.allow_messages), senders, p.messages) { i -> p.messages = i; refreshBehavior() }
        }
        val callRow = Ui.hbox(this)
        callRow.addView(callsBtn, Ui.lp(0, weight = 1f))
        callRow.addView(msgBtn, Ui.lp(0, weight = 1f))
        detailBox.addView(callRow)
        fun check(label: Int, get: () -> Boolean, set: (Boolean) -> Unit): CheckBox = CheckBox(this).apply {
            text = s(label); isChecked = get()
            setOnCheckedChangeListener { _, v -> set(v); refreshBehavior() }
        }
        detailBox.addView(check(R.string.allow_media, { p.media }, { p.media = it }))
        btCheck = check(R.string.allow_media_bt, { p.mediaOnBt }, { p.mediaOnBt = it })
        detailBox.addView(btCheck)
        // which Bluetooth devices count: a setting of this profile (it used to be one setting for the whole app)
        val btLabels = listOf(s(R.string.bt_all), s(R.string.bt_earphone))
        btScopeBtn = Ui.button(this, "") {
            Ui.choose(this, s(R.string.bt_target), btLabels, p.btScope) { i -> p.btScope = i; refreshBehavior() }
        }
        detailBox.addView(btScopeBtn)
        detailBox.addView(check(R.string.allow_alarms, { p.alarms }, { p.alarms = it }))
        detailBox.addView(check(R.string.allow_system, { p.system }, { p.system = it }))
        detailBox.addView(check(R.string.allow_reminders, { p.reminders }, { p.reminders = it }))
        detailBox.addView(check(R.string.allow_events, { p.events }, { p.events = it }))
        // apps that may interrupt: a system setting shared by all profiles, so it is a link, not a checkbox
        detailBox.addView(Ui.button(this, s(R.string.apps_open_btn)) { Ui.openPrioritySettings(this) })
        detailBox.addView(Ui.text(this, s(R.string.apps_note), 12f, secondary = true))
    }

    /** "What makes a sound with this setting" - recomputed on every change. */
    private fun updateMatrix() {
        effectBox.removeAllViews()
        effectBox.addView(Ui.matrixView(this, p))
    }

    private fun refreshBehavior() {
        for ((d, rb) in dndRadios) rb.isChecked = p.dnd == d
        // the options belong to the notification filter: grey while another DND level is chosen
        val pri = p.dnd == Dnd.PRIORITY
        setEnabledDeep(detailBox, pri)
        detailBox.alpha = if (pri) 1f else 0.4f
        forcedNote.visibility = if (p.dnd == Dnd.NONE) View.VISIBLE else View.GONE
        callsBtn.text = "📞 " + s(R.string.allow_calls) + ": " + Describe.senders(this, p.calls)
        msgBtn.text = "💬 " + s(R.string.allow_messages) + ": " + Describe.senders(this, p.messages)
        btCheck.visibility = if (p.media) View.GONE else View.VISIBLE
        btScopeBtn.visibility = if (p.media || !p.mediaOnBt) View.GONE else View.VISIBLE
        btScopeBtn.text = s(R.string.bt_target) + ": " + s(if (p.btScope == BtMode.EARPHONE_ONLY) R.string.bt_earphone else R.string.bt_all)
        updateMatrix()
        updatePreview()
    }

    // ---- look -----------------------------------------------------------------------------

    private fun curKey(): String? = if (editOn) p.iconKeyOn else p.iconKey
    private fun curEmoji(): String = if (editOn) p.emojiOn else p.emoji
    private fun curImage(): String? = if (editOn) p.imageFileOn else p.imageFile

    /** [key]: built-in icon or null; [emoji]: new emoji or null to leave it. Own pictures belong to the OFF icon only. */
    private fun setIcon(key: String?, emoji: String?) {
        if (editOn) { p.iconKeyOn = key; if (emoji != null) p.emojiOn = emoji; p.imageFileOn = null }
        else { p.iconKey = key; if (emoji != null) p.emoji = emoji; p.imageFile = null }
    }

    private fun marginRow(label: Int, get: () -> Int, set: (Int) -> Unit): View {
        val col = Ui.vbox(this)
        val t = Ui.text(this, "", 12f, secondary = true)
        fun show() { t.text = s(label, get()) }
        show()
        col.addView(t)
        col.addView(SeekBar(this).apply {
            max = 24; progress = get()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { set(v); show(); updatePreview() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        return col
    }

    private var lookDialog: android.app.Dialog? = null
    private var lookTab = 0 // 0 = off look, 1 = on look, 2 = shape / margins
    private lateinit var lookPreview: LinearLayout
    private lateinit var lookTabBox: LinearLayout
    private lateinit var lookBody: LinearLayout

    private fun buildLook(root: LinearLayout) {
        onInit = p.onSep || p.iconKeyOn != null || p.emojiOn != "🔔"
        val card = Ui.card(this)
        lookCard = card
        card.addView(Ui.text(this, s(R.string.look_title), 16f, bold = true))
        card.addView(Ui.text(this, s(R.string.look_hint), 12f, secondary = true))
        card.addView(Ui.button(this, s(R.string.look_edit)) { openLook() })
        root.addView(card)
    }

    /** The look (icon, colours, shape, margins) is edited in its own window; the preview stays on top of it. */
    private fun openLook(startTab: Int? = null) {
        lookDialog?.dismiss()
        if (startTab != null) lookTab = startTab
        val d = android.app.Dialog(this, R.style.LookDialog)
        val box = Ui.vbox(this)
        val header = Ui.hbox(this).apply { setPadding(dp(16), dp(8), dp(8), dp(4)) }
        header.addView(Ui.text(this, s(R.string.look_title), 20f, bold = true), Ui.lp(0, weight = 1f))
        header.addView(Ui.button(this, s(R.string.done)) { d.dismiss() })
        box.addView(header)
        lookPreview = Ui.hbox(this).apply { gravity = Gravity.CENTER }
        box.addView(lookPreview, Ui.lp())
        lookTabBox = Ui.vbox(this)
        box.addView(lookTabBox, Ui.lp())
        lookBody = Ui.vbox(this).apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }
        box.addView(ScrollView(this).apply { addView(lookBody) }, Ui.lp(h = 0, weight = 1f))
        box.applySystemInsets()
        d.setContentView(box)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.setOnDismissListener { lookDialog = null; editOn = false; updatePreview() }
        lookDialog = d
        d.show()
        renderLook()
        updatePreview()
    }

    private fun renderLook() {
        if (lookDialog == null) return
        editOn = lookTab == 1
        lookTabBox.removeAllViews()
        lookTabBox.addView(Ui.tabBar(this, listOf(s(R.string.look_tab_off), s(R.string.look_tab_on), s(R.string.look_tab_shape)), lookTab, { lookTab = it; renderLook() }))
        lookBody.removeAllViews()
        if (lookTab == 2) renderShape() else renderIconAndColors()
    }

    private fun renderIconAndColors() {
        val on = lookTab == 1
        if (on) {
            lookBody.addView(CheckBox(this).apply {
                text = s(R.string.icon_sep); isChecked = p.onSep
                setOnCheckedChangeListener { _, v ->
                    p.onSep = v
                    if (v && !onInit) { // first time only: start from the current OFF icon, later choices are kept
                        p.iconKeyOn = p.iconKey
                        if (p.imageFile == null) p.emojiOn = p.emoji
                        onInit = true
                    }
                    refreshLook()
                }
            })
        }
        if (!on || p.onSep) {
            lookBody.addView(Ui.text(this, s(R.string.icon_flat_pick), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
            iconBox = GridLayout(this).apply { columnCount = 6 }
            lookBody.addView(iconBox)
            fillIcons()

            lookBody.addView(Ui.text(this, s(R.string.icon_pick), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
            val grid = GridLayout(this).apply { columnCount = 8 }
            for (e in EMOJIS) {
                grid.addView(Ui.text(this, e, 26f).apply {
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    setOnClickListener { setIcon(null, e); refreshLook() }
                })
            }
            lookBody.addView(grid)
            emojiEdit = EditText(this).apply {
                hint = s(R.string.icon_custom)
                setText(curEmoji())
                filters = arrayOf(InputFilter.LengthFilter(8))
                setSingleLine()
                // typing: only the preview and the icon marks follow (the whole page is not rebuilt, the keyboard stays)
                onChange { if (hasFocus() && it.isNotEmpty()) { setIcon(null, it); fillIcons(); updatePreview() } }
            }
            lookBody.addView(emojiEdit)
            val imgRow = Ui.hbox(this)
            imgRow.addView(Ui.button(this, s(R.string.image_choose)) {
                try {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_IMG,
                    )
                } catch (e: Exception) {
                    Ui.toast(this, s(R.string.err_image, e.javaClass.simpleName + ": " + e.message), true)
                }
            }, Ui.lp(0, weight = 1f))
            if (curImage() != null) imgRow.addView(Ui.button(this, s(R.string.image_remove)) {
                if (editOn) p.imageFileOn = null else p.imageFile = null; refreshLook()
            }, Ui.lp(0, weight = 1f))
            lookBody.addView(imgRow)
        } else {
            lookBody.addView(Ui.text(this, s(R.string.icon_sep_hint), 12f, secondary = true).apply { setPadding(0, dp(4), 0, dp(4)) })
        }
        if (on) {
            lookBody.addView(colorRow(s(R.string.color_fg_on), false, { p.fgOn }, { p.fgOn = it }))
            lookBody.addView(colorRow(s(R.string.color_bg_on), true, { p.bgOn }, { p.bgOn = it }))
            lookBody.addView(alphaRow({ p.bgOn }, { p.bgOn = it }, { p.bgOnA }, { p.bgOnA = it }))
        } else {
            lookBody.addView(colorRow(s(R.string.color_fg_off), false, { p.fgOff }, { p.fgOff = it }))
            lookBody.addView(colorRow(s(R.string.color_bg_off), true, { p.bgOff }, { p.bgOff = it }))
            lookBody.addView(alphaRow({ p.bgOff }, { p.bgOff = it }, { p.bgOffA }, { p.bgOffA = it }))
        }
    }

    private fun renderShape() {
        val shapeLabels = listOf(R.string.shape_pill, R.string.shape_round, R.string.shape_round_l, R.string.shape_square).map { s(it) }
        val shapeBtn = Ui.button(this, "") {}
        // listed from the roundest: circle/capsule, large rounded corners, rounded corners, square (stored numbers unchanged)
        val shapeOrder = listOf(Shape.PILL, Shape.ROUND_L, Shape.ROUND, Shape.SQUARE)
        fun setShapeText() { shapeBtn.text = s(R.string.shape) + ": " + shapeLabels[p.shape] }
        shapeBtn.setOnClickListener {
            Ui.choose(this, s(R.string.shape), shapeOrder.map { shapeLabels[it] }, shapeOrder.indexOf(p.shape)) { i -> p.shape = shapeOrder[i]; setShapeText(); updatePreview() }
        }
        setShapeText()
        lookBody.addView(shapeBtn)

        val sizeLabels = listOf(R.string.size_small, R.string.size_medium, R.string.size_large).map { s(it) }
        val sizeBtn = Ui.button(this, "") {}
        fun setSizeText() { sizeBtn.text = s(R.string.icon_size) + ": " + sizeLabels[p.iconSize] }
        sizeBtn.setOnClickListener { Ui.choose(this, s(R.string.icon_size), sizeLabels, p.iconSize) { i -> p.iconSize = i; setSizeText(); updatePreview() } }
        setSizeText()
        lookBody.addView(sizeBtn)

        lookBody.addView(CheckBox(this).apply {
            text = s(R.string.keep_ratio); isChecked = p.keepRatio
            setOnCheckedChangeListener { _, v -> p.keepRatio = v }
        })
        lookBody.addView(Ui.text(this, s(R.string.keep_ratio_hint), 11f, secondary = true))
        lookBody.addView(Ui.text(this, s(R.string.margin_title), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        lookBody.addView(Ui.text(this, s(R.string.margin_hint), 11f, secondary = true))
        lookBody.addView(marginRow(R.string.margin_top, { p.mT }, { p.mT = it }))
        lookBody.addView(marginRow(R.string.margin_bottom, { p.mB }, { p.mB = it }))
        lookBody.addView(marginRow(R.string.margin_left, { p.mL }, { p.mL = it }))
        lookBody.addView(marginRow(R.string.margin_right, { p.mR }, { p.mR = it }))
        lookBody.addView(CheckBox(this).apply {
            text = s(R.string.show_label); isChecked = p.showLabel
            setOnCheckedChangeListener { _, v -> p.showLabel = v; updatePreview() }
        })
    }

    private fun fillIcons() {
        iconBox.removeAllViews()
        val tint = android.content.res.ColorStateList.valueOf(Ui.text(this, "").currentTextColor)
        for ((key, res) in FlatIcons.ALL) {
            val selected = curImage() == null && curKey() == key
            iconBox.addView(ImageView(this).apply {
                setImageResource(res)
                imageTintList = tint
                setPadding(dp(8), dp(8), dp(8), dp(8))
                contentDescription = key
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    if (selected) { setColor(0x331F6FEB); setStroke(dp(2), 0xFF1F6FEB.toInt()) } else setStroke(dp(1), 0x33808080)
                }
                layoutParams = GridLayout.LayoutParams().apply { width = dp(46); height = dp(46); setMargins(dp(3), dp(3), dp(3), dp(3)) }
                setOnClickListener { setIcon(key, null); refreshLook() }
            })
        }
    }

    /** After any change of the look: the open look window is rebuilt, the previews follow. */
    private fun refreshLook() {
        renderLook()
        updatePreview()
    }

    /** Opacity of the background colour lives in the colour chooser; this row shows the value and opens it. */
    private fun alphaRow(getC: () -> Int?, setC: (Int?) -> Unit, get: () -> Int, set: (Int) -> Unit): View {
        return Ui.button(this, s(R.string.bg_alpha_open, get())) {
            val cur = getC()
            val real = cur != null && cur != ColorMark.TRANSPARENT
            val start = if (real) cur!! else 0xFF1F6FEB.toInt()
            ColorPicker.show(this, start, get()) { c, al ->
                // only the opacity was changed: keep "automatic" (follows light/dark) or "no background" as it was
                if (real || c != start) setC(c)
                set(al); refreshLook()
            }
        }
    }

    private fun colorRow(label: String, allowNone: Boolean, get: () -> Int?, set: (Int?) -> Unit): View {
        val col = Ui.vbox(this).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(Ui.text(this, label, 12f, secondary = true))
        val row = Ui.hbox(this)
        val cur = get()
        val real = cur != null && cur != ColorMark.TRANSPARENT
        row.addView(swatch(null, cur == null) { set(null); refreshLook() })
        // background only: "no background" (the button stays tappable, just nothing is painted behind the icon)
        if (allowNone) row.addView(noneSwatch(cur == ColorMark.TRANSPARENT) { set(ColorMark.TRANSPARENT); refreshLook() })
        for (c in PALETTE) row.addView(swatch(c, real && (cur!! or 0xFF000000.toInt()) == c) { set(c); refreshLook() })
        // the rainbow circle: any colour
        val custom = real && PALETTE.none { (cur!! or 0xFF000000.toInt()) == it }
        row.addView(rainbowSwatch(custom) {
            ColorPicker.show(this, if (real) cur!! else 0xFF1F6FEB.toInt()) { c -> set(c); refreshLook() }
        })
        col.addView(HorizontalScrollView(this).apply { addView(row); isHorizontalScrollBarEnabled = false })
        return col
    }

    private fun noneSwatch(selected: Boolean, onClick: () -> Unit): View {
        val size = dp(34)
        val v = Ui.text(this, s(R.string.color_transparent), 11f).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = size / 2f
                setStroke(dp(if (selected) 3 else 1), if (selected) 0xFF1F6FEB.toInt() else 0xFF808080.toInt(), dp(4).toFloat(), dp(3).toFloat())
            }
        }
        v.layoutParams = LinearLayout.LayoutParams(dp(48), size).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        v.contentDescription = s(R.string.color_transparent)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun rainbowSwatch(selected: Boolean, onClick: () -> Unit): View {
        val size = dp(34)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.SWEEP_GRADIENT
                colors = intArrayOf(0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(), 0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt())
                setStroke(dp(if (selected) 3 else 1), if (selected) 0xFF1F6FEB.toInt() else 0x55808080)
            }
        }
        v.layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        v.contentDescription = s(R.string.color_custom)
        v.setOnClickListener { onClick() }
        return v
    }

    private fun swatch(color: Int?, selected: Boolean, onClick: () -> Unit): View {
        val size = dp(34)
        val v: View = if (color == null) {
            Ui.text(this, s(R.string.color_auto), 11f).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = size / 2f; setStroke(dp(if (selected) 3 else 1), 0xFF808080.toInt()) }
            }
        } else View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(color)
                setStroke(dp(if (selected) 3 else 1), if (selected) 0xFF1F6FEB.toInt() else 0x55808080)
            }
        }
        v.layoutParams = LinearLayout.LayoutParams(if (color == null) dp(48) else size, size).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
        v.contentDescription = if (color == null) s(R.string.color_auto) else String.format("#%06X", color and 0xFFFFFF)
        v.setOnClickListener { onClick() }
        return v
    }

    // ---- image import ---------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMG || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            importImage(uri)
            refreshLook()
        } catch (e: Exception) {
            // show WHY it failed instead of failing silently
            Ui.toast(this, s(R.string.err_image, e.javaClass.simpleName + ": " + e.message), true)
        }
    }

    /** Copied into the app's private folder (small PNG), so no URI permission can ever expire. */
    private fun importImage(uri: Uri) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            ?: throw IllegalStateException("cannot open the file")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("not an image")
        if (bounds.outWidth.toLong() * bounds.outHeight > 60_000_000L) throw IllegalStateException("image too large")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("cannot decode the image")
        val scale = 256f / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).roundToInt().coerceAtLeast(1), (bmp.height * scale).roundToInt().coerceAtLeast(1), true)
        } else bmp
        val name = "${p.id}_${System.currentTimeMillis()}.png"
        val f = Store.imageFile(this, name)
        FileOutputStream(f).use { if (!out.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IllegalStateException("could not write the image") }
        // read it back once: proves the saved copy is usable
        if (!f.exists() || f.length() == 0L || BitmapFactory.decodeFile(f.path) == null) {
            f.delete()
            throw IllegalStateException("saved image cannot be read")
        }
        created.add(name)
        if (editOn) { p.imageFileOn = name; p.iconKeyOn = null } else { p.imageFile = name; p.iconKey = null }
    }

    // ---- save / delete --------------------------------------------------------------------

    private fun buildButtons(root: LinearLayout) {
        val row = Ui.hbox(this)
        row.addView(Ui.button(this, getString(android.R.string.cancel)) { finish() }, Ui.lp(0, weight = 1f))
        row.addView(Ui.button(this, s(R.string.save)) { save() }, Ui.lp(0, weight = 1f))
        root.addView(row)
        if (!isNew && p.preset >= 0) {
            root.addView(Ui.text(this, s(R.string.reset_hint), 12f, secondary = true).apply { setPadding(0, dp(12), 0, 0) })
            root.addView(Ui.button(this, s(R.string.reset_preset)) {
                AlertDialog.Builder(this)
                    .setMessage(s(R.string.reset_confirm, p.name))
                    .setPositiveButton(s(R.string.reset_preset)) { _, _ ->
                        saved = true
                        ProfileOps.resetPreset(this, p)
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
        }
        if (!isNew) {
            root.addView(Ui.button(this, s(R.string.menu_copy)) {
                ProfileOps.copy(this, p)
                getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", 0).apply() // the copy lives in the left tab
                finish()
            })
            if (p.preset < 0) root.addView(Ui.button(this, s(R.string.delete)) {
                AlertDialog.Builder(this)
                    .setMessage(s(R.string.delete_confirm, p.name))
                    .setPositiveButton(s(R.string.delete)) { _, _ -> delete() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            })
        }
    }

    private fun save() {
        if (p.name.isBlank()) p.name = s(R.string.new_profile)
        val st = Store.state(this)
        val before = Store.profile(this, p.id) // what is really applied on the phone if it is on now
        Store.upsert(this, p)
        val keep = p.imageFiles()
        created.filter { it !in keep }.forEach { Store.imageFile(this, it).delete() }
        listOfNotNull(origImage, origImageOn).forEach { if (it !in keep) Store.imageFile(this, it).delete() }
        saved = true
        if (st.activeId == p.id) {
            // it is on right now: apply the edited version, keeping the same end time
            val r = Engine.activate(this, p, st.expiresAt, previousContent = before)
            if (!r.ok || r.message != null) Ui.toast(this, r.message ?: "", true)
        }
        Widgets.updateAll(this)
        Schedules.reschedule(this)
        finish()
    }

    private fun delete() {
        saved = true
        created.forEach { Store.imageFile(this, it).delete() }
        ProfileOps.delete(this, p)
        finish()
    }
}
