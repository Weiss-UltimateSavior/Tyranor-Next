package com.tyranor.next.ui.settings

import com.tyranor.next.ui.common.motion.ProvideEntrance
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.game.model.ScanGameIntents
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.ui.runtime.RuntimeModuleSettingsScreen

class PerGameSettingsActivity : AppScreenActivity() {
    /** 基类先加载页面环境；没有有效游戏输入时直接退出，避免创建无目标的覆盖设置页。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val game = intent.readScanGame()
        if (game == null) {
            finish()
            return
        }

        setAppScreenContent {
            ProvideEntrance {
                if (game.engine == EngineType.RUNTIME_MODULE) {
                    RuntimeModuleSettingsScreen(game = game)
                } else {
                    PerGameSettingsScreen(game = game)
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context, game: ScanGame): Intent =
            ScanGameIntents.putGame(Intent(context, PerGameSettingsActivity::class.java), game)

        private fun Intent.readScanGame(): ScanGame? = ScanGameIntents.getGame(this)
    }
}
