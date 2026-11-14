package io.github.ghghuser.correctmode

/** Built-in flat icons (24dp vectors, drawn in the widget's icon colour). The key is what is stored. */
object FlatIcons {
    val ALL: List<Pair<String, Int>> = listOf(
        "bell" to R.drawable.ic_f_bell,
        "bell_off" to R.drawable.ic_f_bell_off,
        "vibrate" to R.drawable.ic_f_vibrate,
        "volume_up" to R.drawable.ic_f_volume_up,
        "volume_off" to R.drawable.ic_f_volume_off,
        "dnd" to R.drawable.ic_f_dnd,
        "moon" to R.drawable.ic_f_moon,
        "headphones" to R.drawable.ic_f_headphones,
        "phone" to R.drawable.ic_f_phone,
        "car" to R.drawable.ic_f_car,
        "work" to R.drawable.ic_f_work,
        "bed" to R.drawable.ic_f_bed,
        "book" to R.drawable.ic_f_book,
        "music" to R.drawable.ic_f_music,
        "heart" to R.drawable.ic_f_heart,
        "flight" to R.drawable.ic_f_flight,
    )
    private val map = ALL.toMap()
    fun has(key: String): Boolean = map.containsKey(key)
    fun res(key: String?): Int? = if (key == null) null else map[key]
}
