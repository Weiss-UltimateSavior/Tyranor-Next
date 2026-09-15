package com.tyranor.next.core.game.scan

import android.content.Context
import android.util.Log
import com.core.engine.EnginePrefs
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.storage.GameLibraryFacade

/**
 * Siglus 标题回写导入（引擎进程无法访问 Room，故走 prefs 回写协议）：
 *
 * 1. App 启动前登记 `siglus_uri.<pathHash>` / `siglus_default_title.<pathHash>`；
 * 2. 宿主在引擎创建成功后把 Gameexe GAMENAME 写入 `siglus_title.<pathHash>`；
 * 3. 本对象在游戏库加载时导入：仅当库中标题仍等于登记的目录名（未被用户改名）时才覆盖。
 */
object SiglusTitleFeedback {

    private const val TAG = "SiglusTitleFeedback"

    /** 导入待处理的标题回写；有变更时经 [GameLibraryFacade] 差量落库。 */
    fun import(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(EnginePrefs.APP_PREFS, Context.MODE_PRIVATE)
        val titles = prefs.all.filterKeys { it.startsWith(EnginePrefs.KEY_SIGLUS_TITLE_PREFIX) }
        if (titles.isEmpty()) {
            return
        }

        val games = GameLibraryFacade.loadGames(app)
        val byUri = games.associateBy { it.uri }
        val updates = HashMap<String, String>()
        for ((key, value) in titles) {
            val hash = key.removePrefix(EnginePrefs.KEY_SIGLUS_TITLE_PREFIX)
            val title = (value as? String)?.trim().orEmpty()
            if (hash.isEmpty() || title.isEmpty()) continue
            val uri = prefs.getString(EnginePrefs.KEY_SIGLUS_URI_PREFIX + hash, null) ?: continue
            val game = byUri[uri] ?: continue
            if (game.engine != EngineType.SIGLUS) continue
            val defaultTitle = prefs.getString(EnginePrefs.KEY_SIGLUS_DEFAULT_TITLE_PREFIX + hash, null)
            if (!defaultTitle.isNullOrBlank() && game.title != defaultTitle) {
                // 用户手工改过标题：保留用户选择
                continue
            }
            if (game.title == title) continue
            updates[uri] = title
        }
        if (updates.isEmpty()) {
            return
        }

        GameLibraryFacade.updateGames(app) { current ->
            current.map { game ->
                val title = updates[game.uri] ?: return@map game
                game.copy(title = title)
            }
        }
        Log.i(TAG, "imported siglus titles: ${updates.size}")
    }
}
