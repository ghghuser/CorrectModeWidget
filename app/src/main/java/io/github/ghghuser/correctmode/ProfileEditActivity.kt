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
import android.widget.ScrollView
import android.widget.TextView
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Create / edit one Ringer + DND combination and the look of its widget button. */
class ProfileEditActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Loc.wrap(newBase))

    private companion object {
        const val REQ_IMG = 10
        val EMOJIS = listOf(
            "🤫", "📳", "🔕", "🔔", "🌙", "😴", "🎧", "🚗",
            "🏢", "📚", "🎬", "🍽", "✈", "🏥", "🙏", "⛔",
            "🔇", "🔈", "🧘", "💼", "🏠", "🚆", "🎮", "🎵",
        )
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

    private fun s(id: Int, vararg a: Any): String = getString(id, *a)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ex = Store.profile(this, intent?.getStringExtra("id"))
        isNew = ex == null
        p = ex?.copy() ?: Profile(Store.newId(), s(R.string.new_profile))
        origImage = ex?.imageFile
        title = s(if (isNew) R.string.profile_new_title else R.string.profile_edit_title)

        val root = Ui.vbox(this).apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        buildNameAndPreview(root)
        buildBehavior(root)
        buildLook(root)
        buildButtons(root)
        val scroll = ScrollView(this).apply { addView(root) }
        scroll.applyBottomInsets()
        setContentView(scroll)
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
            col.addView(Widgets.preview(this, p, on, 72))
            col.addView(Ui.text(this, s(if (on) R.string.state_on else R.string.state_off), 11f, secondary = true))
            previewBox.addView(col)
        }
    }

    // ---- name + preview -------------------------------------------------------------------

    private fun buildNameAndPreview(root: LinearLayout) {
        previewBox = Ui.hbox(this)
        root.addView(previewBox)
        root.addView(Ui.text(this, s(R.string.name), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        root.addView(EditText(this).apply {
            setText(p.name)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(30))
            setSingleLine()
            onChange { p.name = it; effectText.text = Describe.detail(this@ProfileEditActivity, p); updatePreview() }
        })
    }

    // ---- behaviour ------------------------------------------------------------------------

    private fun buildBehavior(root: LinearLayout) {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.behavior_title), 16f, bold = true))

        card.addView(Ui.text(this, s(R.string.ringer_label) + " (" + s(R.string.ringer_hint) + ")", 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        card.addView(radios(listOf(Ringer.NORMAL, Ringer.VIBRATE, Ringer.MUTE).map { Describe.ringer(this, it) }, p.ringer) {
            p.ringer = it; refreshBehavior()
        })

        card.addView(Ui.text(this, s(R.string.dnd_label) + " (" + s(R.string.dnd_hint) + ")", 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        card.addView(radios(listOf(Dnd.OFF, Dnd.PRIORITY, Dnd.ALARMS, Dnd.NONE).map { Describe.dnd(this, it) }, p.dnd) {
            p.dnd = it; refreshBehavior()
        })

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
        card.addView(detailBox)

        effectText = Ui.text(this, "", 13f).apply { setPadding(0, dp(8), 0, dp(4)) }
        card.addView(effectText)

        val durLabels = DURATIONS.map { Ui.durationLabel(this, it) }
        val durBtn = Ui.button(this, "") {}
        fun setDurText() { durBtn.text = s(R.string.duration_label) + ": " + Ui.durationLabel(this, p.duration) }
        durBtn.setOnClickListener {
            Ui.choose(this, s(R.string.duration_label), durLabels, DURATIONS.indexOf(p.duration)) { i ->
                p.duration = DURATIONS[i]; setDurText()
            }
        }
        setDurText()
        card.addView(durBtn)
        root.addView(card)
    }

    private fun refreshBehavior() {
        detailBox.visibility = if (p.dnd == Dnd.PRIORITY) View.VISIBLE else View.GONE
        forcedNote.visibility = if (p.dnd == Dnd.ALARMS || p.dnd == Dnd.NONE) View.VISIBLE else View.GONE
        callsBtn.text = s(R.string.allow_calls) + ": " + Describe.senders(this, p.calls)
        msgBtn.text = s(R.string.allow_messages) + ": " + Describe.senders(this, p.messages)
        btCheck.visibility = if (p.media) View.GONE else View.VISIBLE
        effectText.text = Describe.detail(this, p)
        updatePreview()
    }

    // ---- look -----------------------------------------------------------------------------

    private fun buildLook(root: LinearLayout) {
        val card = Ui.card(this)
        card.addView(Ui.text(this, s(R.string.look_title), 16f, bold = true))

        card.addView(Ui.text(this, s(R.string.icon_pick), 13f, secondary = true).apply { setPadding(0, dp(8), 0, 0) })
        val grid = GridLayout(this).apply { columnCount = 8 }
        for (e in EMOJIS) {
            grid.addView(Ui.text(this, e, 26f).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    p.emoji = e; p.imageFile = null
                    emojiEdit.setText(e)
                    refreshLook()
                }
            })
        }
        card.addView(grid)

        emojiEdit = EditText(this).apply {
            hint = s(R.string.icon_custom)
            setText(p.emoji)
            filters = arrayOf(InputFilter.LengthFilter(8))
            setSingleLine()
            onChange {
                if (hasFocus() && it.isNotEmpty()) { p.emoji = it; p.imageFile = null; refreshLook() }
            }
        }
        card.addView(emojiEdit)

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
            text = s(R.string.show_label); isChecked = p.showLabel
            setOnCheckedChangeListener { _, v -> p.showLabel = v; updatePreview() }
        })
        root.addView(card)
    }

    private fun refreshLook() {
        removeImg.visibility = if (p.imageFile != null) View.VISIBLE else View.GONE
        colorsBox.removeAllViews()
        colorsBox.addView(colorRow(s(R.string.color_bg_off), { p.bgOff }, { p.bgOff = it }))
        colorsBox.addView(colorRow(s(R.string.color_bg_on), { p.bgOn }, { p.bgOn = it }))
        colorsBox.addView(colorRow(s(R.string.color_fg_off), { p.fgOff }, { p.fgOff = it }))
        colorsBox.addView(colorRow(s(R.string.color_fg_on), { p.fgOn }, { p.fgOn = it }))
        updatePreview()
    }

    private fun colorRow(label: String, get: () -> Int?, set: (Int?) -> Unit): View {
        val col = Ui.vbox(this).apply { setPadding(0, dp(6), 0, 0) }
        col.addView(Ui.text(this, label, 12f, secondary = true))
        val row = Ui.hbox(this)
        val cur = get()
        row.addView(swatch(null, cur == null) { set(null); refreshLook() })
        for (c in PALETTE) row.addView(swatch(c, cur != null && (cur or 0xFF000000.toInt()) == c) { set(c); refreshLook() })
        col.addView(HorizontalScrollView(this).apply { addView(row); isHorizontalScrollBarEnabled = false })
        return col
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
    }

    // ---- save / delete --------------------------------------------------------------------

    private fun buildButtons(root: LinearLayout) {
        val row = Ui.hbox(this)
        row.addView(Ui.button(this, getString(android.R.string.cancel)) { finish() }, Ui.lp(0, weight = 1f))
        row.addView(Ui.button(this, s(R.string.save)) { save() }, Ui.lp(0, weight = 1f))
        root.addView(row)
        if (!isNew) {
            root.addView(Ui.button(this, s(R.string.delete)) {
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
        finish()
    }

    private fun delete() {
        if (Store.state(this).activeId == p.id) Engine.release(this, Reason.USER)
        saved = true
        created.forEach { Store.imageFile(this, it).delete() }
        Store.delete(this, p.id)
        Widgets.updateAll(this)
        finish()
    }
}
