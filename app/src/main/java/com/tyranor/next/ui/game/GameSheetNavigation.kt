package com.tyranor.next.ui.game

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tyranor.next.ui.common.motion.staggeredEntrance
import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.ui.common.motion.MotionMode
import com.tyranor.next.ui.common.motion.MotionSheetController
import com.tyranor.next.ui.common.motion.startActivityWithPageTransition
import com.tyranor.next.ui.settings.PerGameSettingsActivity

/** Home and library own separate selections; both clear theirs on ordinary-mode settings navigation. */
internal fun openPerGameSettingsFromSheet(context: Context, game: ScanGame, clearSelection: () -> Unit) {
    startActivityWithPageTransition(context, PerGameSettingsActivity.createIntent(context, game))
    if (!MotionMode.enhanced) clearSelection()
}

internal class GameSheetNavigation(
    private val sheet: MotionSheetController,
    private val owner: LifecycleOwner,
    private val dismiss: () -> Unit,
) {
    fun openDetails(ordinaryDismiss: () -> Unit = dismiss, open: () -> Unit) {
        if (!sheet.interact()) return
        open()
        if (sheet.enhanced) sheet.stayDuringNavigation(owner) else ordinaryDismiss()
    }

    fun onGameLaunched() {
        if (sheet.enhanced) sheet.navigateAway(owner) else dismiss()
    }

    fun close() {
        if (sheet.enhanced) sheet.close() else dismiss()
    }
}

/** The fixed launch action takes beat 1, before the file picker and the remaining details. */
internal fun Modifier.gameSheetEntrance(order: Int): Modifier =
    staggeredEntrance(order, distance = 14.dp, stepMillis = 16L)
