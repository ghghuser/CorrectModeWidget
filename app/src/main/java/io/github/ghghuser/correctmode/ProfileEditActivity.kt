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
import android.widget.RadioGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Create / edit one Ringer + DND combination and the look of its widget button. */
class ProfileEditActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        const val REQ_IMG = 10
        // a short list: any emoji can be typed below, and any picture can be chosen
        val EMOJIS = listOf("🤫", "📳", "🔕", "🔔", "🌙", "🎧", "🚗", "🏢", "📚", "🎬", "🏠", "💼")
        val PALETTE = listOf(
            0xFF1F6FEBL, 0xFF0F9D8AL, 0xFF2E7D32L, 0xFFF9A825L, 0xFFEF6C00L, 0xFFD32F2FL, 0xFFC2185BL,
            0xFF7B1FA2L, 0xFF455A64L, 0xFF000000L, 0xFFFFFFFFL, 0xFFE3E6EAL, 0xFF30343AL, 0xFF8AB4FFL,
        ).map { it.toInt() }
        val DURATIONS = listOf(Dur.ASK, Dur.NONE, 15, 30, 60, 120, 180, 240)
    }

    private lateinit var p: Profile
    private var isNew = false
    private var origImage: String? = null
    private val created = ArrayList<String>()
    private var saved = false

    private lateinit var previewBox: LinearLayout
    private lateinit var detailBox: LinearLayout
    private lateinit var forcedNote: TextView
    private lateinit var effectText: TextView
    private lateinit var btCheck: CheckBox
    private lateinit var callsBtn: android.widget.Button
    private lateinit var msgBtn: android.widget.Button
    private lateinit var colorsBox: LinearLayout
    private lateinit var removeImg: android.widget.Button
    private lateinit var emojiEdit: EditText
    private lateinit var imgRow: LinearLayout
    private lateinit var stateRow: LinearLayout
    /** true = the icon grid / emoji field edit the icon used while the profile is ON (only with p.onSep) */
    private var editOn = false
    /** the on-state icon has been given a first value (set once, kept when the checkbox is toggled) */
    private var onInit = false
    private lateinit var iconBox: GridLayout
    private lateinit var lookCard: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var modeHolder: LinearLayout
    private lateinit var choiceBox: LinearLayout
    private lateinit var schedBox: LinearLayout
    private var modeSet = true

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ex = Store.profile(this, intent?.getStringExtra("id"))
        isNew = ex == null
        val fromPattern = intent?.getIntExtra("pattern", -1) ?: -1
        p = ex?.copy() ?: if (fromPattern in Patterns.ALL.indices) Store.freshPreset(this, fromPattern, Store.newId(), 0, -1)
        else Profile(Store.newId(), s(R.string.new_profile), tab = (intent?.getIntExtra("tab", 0) ?: 0).coerceIn(0, 1))
        origImage = ex?.imageFile
        title = s(if (isNew) R.string.profile_new_title else R.string.profile_edit_title)

        val root = Ui.vbox(this).apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        buildNameAndPreview(root)
        modeSet = try { getSharedPreferences("ui", MODE_PRIVATE).getBoolean("editModeSet", true) } catch (e: Exception) { true }
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

    private fun radios(labels: List<String>, current: Int, onPick: (Int) -> Unit): RadioGroup {
        val g = RadioGroup(this)
        labels.forEachIndexed { i, l ->
            g.addView(RadioButton(this).apply {
                text = l
                id = View.generateViewId()
                isChecked = i == current
                setOnClickListener { onPick(i) }
            })
        }
        return g
    }

    private fun updatePreview() {
        previewBox.removeAllViews()
        for (on in listOf(false, true)) {
            val col = Ui.vbox(this).apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), 0, dp(6), 0) }
            col.addView(Widgets.preview(this, p, on, 72, outline = true))
            col.addView(Ui.text(this, s(if (on) R.string.state_on else R.string.state_off), 11f, secondary = true))
            // tap the preview: jump to the look settings
            col.setOnClickListener { scroll.post { scroll.smoothScrollTo(0, lookCard.top) } }
            previewBox.addView(col)
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

        // two ways to pick the same two things (ringer + DND): a ready-made combination, or each one
        modeHolder = Ui.vbox(this)
        choiceBox = Ui.vbox(this)
        card.addView(modeHolder)
        card.addView(choiceBox)
        fillMode()
        fillChoice()

        forcedNote = Ui.text(this, s(R.string.dnd_forced_note), 12f, secondary = true)
        card.addView(forcedNote)

        detailBox = Ui.vbox(this)
        detailBox.addView(Ui.text(this, s(R.string.priority_allows), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        val senders = listOf(0, 1, 2, 3).map { Describe.senders(this, it) }
        callsBtn = Ui.button(this, "") {
            Ui.choose(this, s(R.string.allow_calls), senders, p.calls) { i -> p.calls = i; refreshBehavior() }
        }
        msgBtn = Ui.button(this, "") {
            Ui.choose(this, s(R.string.allow_messages), senders, p.messages) { i -> p.messages = i; refreshBehavior() }
        }
        detailBox.addView(callsBtn)
        detailBox.addView(msgBtn)
        detailBox.addView(CheckBox(this).apply {
            text = s(R.string.allow_media); isChecked = p.media
            setOnCheckedChangeListener { _, v -> p.media = v; refreshBehavior() }
        })
        btCheck = CheckBox(this).apply {
            text = s(R.string.allow_media_bt); isChecked = p.mediaOnBt
            setOnCheckedChangeListener { _, v -> p.mediaOnBt = v; refreshBehavior() }
        }
        detailBox.addView(btCheck)
        detailBox.addView(CheckBox(this).apply {
            text = s(R.string.allow_alarms); isChecked = p.alarms
            setOnCheckedChangeListener { _, v -> p.alarms = v; refreshBehavior() }
        })
        detailBox.addView(CheckBox(this).apply {
            text = s(R.string.allow_system); isChecked = p.system
            setOnCheckedChangeListener { _, v -> p.system = v; refreshBehavior() }
        })
        detailBox.addView(CheckBox(this).apply {
            text = s(R.string.allow_reminders); isChecked = p.reminders
            setOnCheckedChangeListener { _, v -> p.reminders = v; refreshBehavior() }
        })
        detailBox.addView(CheckBox(this).apply {
            text = s(R.string.allow_events); isChecked = p.events
            setOnCheckedChangeListener { _, v -> p.events = v; refreshBehavior() }
        })
        // apps that may interrupt: a system setting shared by all profiles, so it is a link, not a checkbox
        detailBox.addView(Ui.button(this, s(R.string.apps_open_btn)) { Ui.openPrioritySettings(this) })
        detailBox.addView(Ui.text(this, s(R.string.wiz_apps_note), 12f, secondary = true))
        card.addView(detailBox)

        effectText = Ui.text(this, "", 13f).apply { setPadding(0, dp(8), 0, dp(4)) }
        card.addView(effectText)

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

    private fun fillMode() {
        modeHolder.removeAllViews()
        modeHolder.addView(Ui.tabBar(this, listOf(s(R.string.mode_set), s(R.string.mode_detail)), if (modeSet) 0 else 1, { i ->
            modeSet = i == 0
            try { getSharedPreferences("ui", MODE_PRIVATE).edit().putBoolean("editModeSet", modeSet).apply() } catch (e: Exception) { }
            fillMode(); fillChoice()
        }))
    }

    private fun fillChoice() {
        choiceBox.removeAllViews()
        if (modeSet) {
            choiceBox.addView(Ui.text(this, s(R.string.mode_set_hint), 12f, secondary = true).apply { setPadding(0, dp(4), 0, dp(4)) })
            // every row: the name, then the six "what sounds" marks, each in a cell of equal width, so they line up
            val radiosList = ArrayList<RadioButton>()
            val current = Patterns.indexOf(p)
            Patterns.ALL.indices.forEach { i ->
                val (r, d) = Patterns.ALL[i]
                val row = Ui.vbox(this).apply { setPadding(0, dp(4), 0, dp(6)) }
                val rb = RadioButton(this).apply {
                    text = s(Patterns.NAMES[i]); isChecked = i == current
                    setOnClickListener {
                        radiosList.forEach { it.isChecked = it === this }
                        p.ringer = Patterns.ALL[i].first; p.dnd = Patterns.ALL[i].second; refreshBehavior()
                    }
                }
                radiosList.add(rb)
                row.addView(rb)
                val cells = Ui.hbox(this).apply { setPadding(dp(32), 0, 0, 0) }
                Describe.matrix(p.copy(ringer = r, dnd = d)).forEach { r2 ->
                    cells.addView(Ui.text(this, r2.emoji + r2.cell.mark, 15f).apply { gravity = Gravity.CENTER }, Ui.lp(0, weight = 1f))
                }
                row.addView(cells)
                choiceBox.addView(row)
            }
            choiceBox.addView(Ui.text(this, s(R.string.legend), 11f, secondary = true))
        } else {
            choiceBox.addView(Ui.text(this, s(R.string.ringer_label) + " (" + s(R.string.ringer_hint) + ")", 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
            choiceBox.addView(radios(listOf(Ringer.NORMAL, Ringer.VIBRATE, Ringer.MUTE).map { Describe.ringer(this, it) }, p.ringer) {
                p.ringer = it; refreshBehavior()
            })
            choiceBox.addView(Ui.text(this, s(R.string.dnd_label) + " (" + s(R.string.dnd_hint) + ")", 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
            choiceBox.addView(radios(listOf(Dnd.OFF, Dnd.PRIORITY, Dnd.ALARMS, Dnd.NONE).map { Describe.dnd(this, it) }, p.dnd) {
                p.dnd = it; refreshBehavior()
            })
        }
    }

    /** "What makes a sound with this setting" - recomputed on every change. */
    private fun updateMatrix() {
        effectText.text = s(R.string.matrix_title) + "\n" + Describe.matrixLines(this, p)
    }

    private fun refreshBehavior() {
        detailBox.visibility = if (p.dnd == Dnd.PRIORITY) View.VISIBLE else View.GONE
        forcedNote.visibility = if (p.dnd == Dnd.ALARMS || p.dnd == Dnd.NONE) View.VISIBLE else View.GONE
        callsBtn.text = s(R.string.allow_calls) + ": " + Describe.senders(this, p.calls)
        msgBtn.text = s(R.string.allow_messages) + ": " + Describe.senders(this, p.messages)
        btCheck.visibility = if (p.media) View.GONE else View.VISIBLE
        updateMatrix()
        updatePreview()
    }

    // ---- look -----------------------------------------------------------------------------

    private fun curKey(): String? = if (editOn) p.iconKeyOn else p.iconKey
    private fun curEmoji(): String = if (editOn) p.emojiOn else p.emoji

    /** [key]: built-in icon or null; [emoji]: new emoji or null to leave it. Own pictures belong to the OFF icon only. */
    private fun setIcon(key: String?, emoji: String?) {
        if (editOn) { p.iconKeyOn = key; if (emoji != null) p.emojiOn = emoji }
        else { p.iconKey = key; if (emoji != null) p.emoji = emoji; p.imageFile = null }
    }

    private fun refreshStateRow() {
        stateRow.removeAllViews()
        stateRow.visibility = if (p.onSep) View.VISIBLE else View.GONE
        if (!p.onSep) return
        stateRow.addView(Ui.button(this, (if (!editOn) "▶ " else "") + s(R.string.icon_edit_off)) { editOn = false; refreshLook() }, Ui.lp(0, weight = 1f))
        stateRow.addView(Ui.button(this, (if (editOn) "▶ " else "") + s(R.string.icon_edit_on)) { editOn = true; refreshLook() }, Ui.lp(0, weight = 1f))
    }

    private fun marginRow(label: Int, get: () -> Int, set: (Int) -> Unit): View {
        val col = Ui.vbox(this)
        val t = Ui.text(this, "", 12f, secondary = true)
        fun show() { t.text = s(label, get()) }
        show()
        col.addView(t)
        col.addView(SeekBar(this).apply {
            max = 32; progress = get()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { set(v); show(); updatePreview() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        return col
    }

    private fun buildLook(root: LinearLayout) {
        onInit = p.onSep || p.iconKeyOn != null || p.emojiOn != "🔔"
        val card = Ui.card(this)
        lookCard = card
        card.addView(Ui.text(this, s(R.string.look_title), 16f, bold = true))

        card.addView(CheckBox(this).apply {
            text = s(R.string.icon_sep); isChecked = p.onSep
            setOnCheckedChangeListener { _, v ->
                p.onSep = v
                if (v) { // first time only: start from the current OFF icon, later choices are kept
                    if (!onInit) {
                        p.iconKeyOn = p.iconKey
                        if (p.imageFile == null) p.emojiOn = p.emoji
                        onInit = true
                    }
                } else editOn = false
                refreshLook()
            }
        })
        stateRow = Ui.hbox(this)
        card.addView(stateRow)
        card.addView(Ui.text(this, s(R.string.icon_flat_pick), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        iconBox = GridLayout(this).apply { columnCount = 6 }
        card.addView(iconBox)

        card.addView(Ui.text(this, s(R.string.icon_pick), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        val grid = GridLayout(this).apply { columnCount = 8 }
        for (e in EMOJIS) {
            grid.addView(Ui.text(this, e, 26f).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    setIcon(null, e)
                    emojiEdit.setText(e)
                    refreshLook()
                }
            })
        }
        card.addView(grid)

        emojiEdit = EditText(this).apply {
            hint = s(R.string.icon_custom)
            setText(curEmoji())
            filters = arrayOf(InputFilter.LengthFilter(8))
            setSingleLine()
            onChange {
                if (hasFocus() && it.isNotEmpty()) { setIcon(null, it); refreshLook() }
            }
        }
        card.addView(emojiEdit)

        imgRow = Ui.hbox(this)
        imgRow.addView(Ui.button(this, s(R.string.image_choose)) {
            try {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), REQ_IMG,
                )
            } catch (e: Exception) {
                Ui.toast(this, s(R.string.err_image, e.javaClass.simpleName + ": " + e.message), true)
            }
        }, Ui.lp(0, weight = 1f))
        removeImg = Ui.button(this, s(R.string.image_remove)) { p.imageFile = null; refreshLook() }
        imgRow.addView(removeImg, Ui.lp(0, weight = 1f))
        card.addView(imgRow)

        colorsBox = Ui.vbox(this)
        card.addView(colorsBox)

        val shapeLabels = listOf(R.string.shape_pill, R.string.shape_round, R.string.shape_round_l, R.string.shape_square).map { s(it) }
        val shapeBtn = Ui.button(this, "") {}
        fun setShapeText() { shapeBtn.text = s(R.string.shape) + ": " + shapeLabels[p.shape] }
        shapeBtn.setOnClickListener { Ui.choose(this, s(R.string.shape), shapeLabels, p.shape) { i -> p.shape = i; setShapeText(); updatePreview() } }
        setShapeText()
        card.addView(shapeBtn)

        val sizeLabels = listOf(R.string.size_small, R.string.size_medium, R.string.size_large).map { s(it) }
        val sizeBtn = Ui.button(this, "") {}
        fun setSizeText() { sizeBtn.text = s(R.string.icon_size) + ": " + sizeLabels[p.iconSize] }
        sizeBtn.setOnClickListener { Ui.choose(this, s(R.string.icon_size), sizeLabels, p.iconSize) { i -> p.iconSize = i; setSizeText(); updatePreview() } }
        setSizeText()
        card.addView(sizeBtn)

        card.addView(CheckBox(this).apply {
            text = s(R.string.keep_ratio); isChecked = p.keepRatio
            setOnCheckedChangeListener { _, v -> p.keepRatio = v }
        })
        card.addView(Ui.text(this, s(R.string.keep_ratio_hint), 11f, secondary = true))
        card.addView(Ui.text(this, s(R.string.margin_title), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        card.addView(marginRow(R.string.margin_top, { p.mT }, { p.mT = it }))
        card.addView(marginRow(R.string.margin_bottom, { p.mB }, { p.mB = it }))
        card.addView(marginRow(R.string.margin_left, { p.mL }, { p.mL = it }))
        card.addView(marginRow(R.string.margin_right, { p.mR }, { p.mR = it }))
        card.addView(CheckBox(this).apply {
            text = s(R.string.show_label); isChecked = p.showLabel
            setOnCheckedChangeListener { _, v -> p.showLabel = v; updatePreview() }
        })
        root.addView(card)
    }

    private fun fillIcons() {
        iconBox.removeAllViews()
        val tint = android.content.res.ColorStateList.valueOf(Ui.text(this, "").currentTextColor)
        for ((key, res) in FlatIcons.ALL) {
            val selected = (editOn || p.imageFile == null) && curKey() == key
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

    private fun refreshLook() {
        fillIcons()
        refreshStateRow()
        imgRow.visibility = if (editOn) View.GONE else View.VISIBLE // pictures are shared by both states
        if (!emojiEdit.hasFocus() && emojiEdit.text.toString() != curEmoji()) emojiEdit.setText(curEmoji())
        removeImg.visibility = if (p.imageFile != null) View.VISIBLE else View.GONE
        colorsBox.removeAllViews()
        // order: icon/text (off), background (off), icon/text (on), background (on)
        colorsBox.addView(colorRow(s(R.string.color_fg_off), false, { p.fgOff }, { p.fgOff = it }))
        colorsBox.addView(colorRow(s(R.string.color_bg_off), true, { p.bgOff }, { p.bgOff = it }))
        colorsBox.addView(alphaRow({ p.bgOffA }, { p.bgOffA = it }))
        colorsBox.addView(colorRow(s(R.string.color_fg_on), false, { p.fgOn }, { p.fgOn = it }))
        colorsBox.addView(colorRow(s(R.string.color_bg_on), true, { p.bgOn }, { p.bgOn = it }))
        colorsBox.addView(alphaRow({ p.bgOnA }, { p.bgOnA = it }))
        updatePreview()
    }

    /** Opacity of the background colour, 0..100 %. Only the preview is redrawn while dragging (not the whole card). */
    private fun alphaRow(get: () -> Int, set: (Int) -> Unit): View {
        val col = Ui.vbox(this)
        val t = Ui.text(this, "", 12f, secondary = true)
        fun show() { t.text = s(R.string.bg_alpha, get()) }
        show()
        col.addView(t)
        col.addView(SeekBar(this).apply {
            max = 100; progress = get()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { set(v); show(); updatePreview() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        })
        return col
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
        p.imageFile = name
        p.iconKey = null
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
        val keep = p.imageFile
        created.filter { it != keep }.forEach { Store.imageFile(this, it).delete() }
        origImage?.let { if (it != keep) Store.imageFile(this, it).delete() }
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
