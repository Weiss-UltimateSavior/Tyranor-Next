package com.tyranor.next.ui.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.tyranor.next.R
import com.tyranor.next.core.i18n.AppLocaleController
import com.tyranor.next.ui.common.notice.AppNotice
import java.util.UUID

class GameShortcutResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppNotice.makeText(
            AppLocaleController.wrap(context),
            R.string.game_desktop_shortcut_added,
            Toast.LENGTH_SHORT,
            AppNotice.Kind.Success,
        ).show()
    }

    companion object {
        internal fun createIntent(context: Context): Intent =
            Intent(context, GameShortcutResultReceiver::class.java).apply {
                data = Uri.fromParts("shortcut-result", UUID.randomUUID().toString(), null)
            }
    }
}
