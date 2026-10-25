package com.tyranor.next.ui.save

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tyranor.next.core.game.save.sync.SaveSyncFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows automatic save-sync feedback only while an Activity is resumed. */
internal fun ComponentActivity.observeSaveSyncFeedback() {
    val activity = this
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            SaveSyncFeedback.notice.filterNotNull().collect { notice ->
                val enabled = shouldShowSaveSyncFeedback(activity.applicationContext)
                if (SaveSyncFeedback.consume(notice) && enabled) {
                    val message = notice.report.toSaveSyncMessage(activity.applicationContext)
                    withContext(Dispatchers.Main.immediate) {
                        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
