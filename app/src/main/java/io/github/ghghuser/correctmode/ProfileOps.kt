package io.github.ghghuser.correctmode

import android.content.Context

/** Delete / copy, shared by the main screen (long-press menu) and the editor. */
object ProfileOps {
    fun delete(ctx: Context, p: Profile) {
        if (Store.state(ctx).activeId == p.id) Engine.release(ctx, Reason.USER)
        Store.delete(ctx, p.id)
        Widgets.updateAll(ctx)
        Schedules.reschedule(ctx)
    }

    /**
     * A copy goes to the LEFT tab (the one for your own profiles), also with its own copy of the picture.
     * The copy's timer is switched off, so two profiles never start at the same time by accident.
     */
    fun copy(ctx: Context, p: Profile): Profile {
        val c = p.copy(
            id = Store.newId(),
            name = (p.name + " " + Loc.str(ctx, R.string.copy_suffix)).take(30),
            tab = 0, schedOn = false, imageFile = null,
        )
        p.imageFile?.let { old ->
            val src = Store.imageFile(ctx, old)
            if (src.isFile) {
                val n = "${c.id}_copy.png"
                try { src.copyTo(Store.imageFile(ctx, n), overwrite = true); c.imageFile = n } catch (e: Exception) { /* no picture in the copy */ }
            }
        }
        Store.upsert(ctx, c)
        Widgets.updateAll(ctx)
        return c
    }
}
