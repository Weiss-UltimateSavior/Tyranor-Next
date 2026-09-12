package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.theme.glassBorder
import com.tyranor.next.theme.AppComponentCornerRadius
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.game.startActivityWithPageTransition
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 引擎设置入口页 Activity：由设置页「引擎设置」条目进入，聚合各引擎细分设置入口。 */
class EngineSettingsMenuActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            EngineSettingsMenuScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, EngineSettingsMenuActivity::class.java)
    }
}

/** 引擎设置入口页：顶部栏遵循 AGENT.md 页面顶部栏统一规范，正文聚合各引擎细分设置入口。 */
@Composable
internal fun EngineSettingsMenuScreen() {
    val ctx = LocalContext.current

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                AppTopBar(
                    title = stringResource(R.string.settings_engine_settings),
                    background = MiuixTheme.colorScheme.background,
                    contentColor = MiuixTheme.colorScheme.onBackground,
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 12.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                EngineSettingsKind.entries.forEach { kind ->
                    item {
                        MiuixCard(modifier = Modifier.fillMaxWidth().glassBorder(), cornerRadius = AppComponentCornerRadius) {
                            Column(Modifier.padding(vertical = 4.dp)) {
                                val title = engineSettingsKindTitle(kind)
                                ArrowPreference(
                                    title = title,
                                    startAction = {
                                        Icon(
                                            painter = painterResource(kind.iconRes),
                                            contentDescription = title,
                                            tint = MiuixTheme.colorScheme.primary,
                                            modifier = Modifier.padding(end = 6.dp).size(24.dp),
                                        )
                                    },
                                    onClick = {
                                        startActivityWithPageTransition(ctx, EngineSettingsActivity.createIntent(ctx, kind))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 引擎细分设置类型：与引擎设置 Activity 共用，标识各引擎配置页。 */
enum class EngineSettingsKind(@param:StringRes val titleRes: Int, @param:DrawableRes val iconRes: Int) {
    KRKR(R.string.engine_settings_krkr_title, R.drawable.ic_settings_engine),
    ONS(R.string.engine_settings_ons_title, R.drawable.ic_settings_engine),
    ARTEMIS(R.string.engine_settings_artemis_title, R.drawable.ic_settings_engine),
    RPG_MAKER(R.string.engine_settings_rpg_maker_title, R.drawable.ic_settings_engine),
    TYRANO(R.string.engine_settings_tyrano_title, R.drawable.ic_settings_engine),
    RENPY(R.string.engine_settings_renpy_title, R.drawable.ic_settings_engine),
}
