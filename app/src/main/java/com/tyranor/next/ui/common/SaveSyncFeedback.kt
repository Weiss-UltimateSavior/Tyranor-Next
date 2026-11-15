package com.tyranor.next.ui.common

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
import com.tyranor.next.ui.common.notice.AppNotice

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
                        // 报告里的失败不是异常：按报告决定类型（失败 / 需要留意），干净的结果保持原来的「提示」
                        val kind = notice.report.noticeKind().takeIf { it != AppNotice.Kind.Success } ?: AppNotice.Kind.Info
                        AppNotice.makeText(activity, message, Toast.LENGTH_SHORT, kind).show()
                    }
                }
            }
        }
    }
}
