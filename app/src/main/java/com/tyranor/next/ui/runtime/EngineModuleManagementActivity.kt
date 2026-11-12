package com.tyranor.next.ui.runtime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.tyranor.next.ui.common.AppScreenActivity

/** 引擎模块管理页 Activity：由妙妙工具侧边栏进入，展示已发现的第三方运行时模块。 */
class EngineModuleManagementActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            EngineModuleManagementScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, EngineModuleManagementActivity::class.java)
    }
}
