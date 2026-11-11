package com.tyranor.next.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyranor.next.BuildConfig
import com.tyranor.next.R
import com.tyranor.next.theme.AppComponentShape
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.SettingsEntryCard
import com.tyranor.next.ui.common.AppScreenActivity
import com.tyranor.next.ui.common.AppTopBar
import com.tyranor.next.ui.common.DialogTextButton
import com.tyranor.next.ui.common.motion.ProvideEntrance
import com.tyranor.next.ui.common.motion.staggeredEntrance

/** 项目仓库地址：关于应用页「项目地址」条目打开的系统浏览器目标。 */
private const val PROJECT_URL = "https://github.com/Weiss-UltimateSavior/Tyranor-Next"

/** 关于应用二级页：应用图标 + 版本号 + 项目地址 / 致谢说明 / 免责声明。 */
class AboutAppActivity : AppScreenActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setAppScreenContent {
            AboutAppScreen()
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, AboutAppActivity::class.java)
    }
}

/**
 * 关于应用页面：应用图标与「Tyranor NEXT <版本号>」同一行居中，下方为项目地址 / 致谢说明 /
 * 免责声明三个条目；整组内容水平居中、距顶栏 60dp，空间不足时可滚动。
 * 「项目地址」跳转系统浏览器；「致谢说明」「免责声明」暂为空实现，后续在此补跳转。
 */
@Composable
internal fun AboutAppScreen() {
    val context = LocalContext.current
    // 免责声明弹窗开关（保存状态，旋转/进程恢复后仍保持）
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }
    val appLabel = stringResource(R.string.app_name)
    val versionLabel = stringResource(R.string.about_app_version, BuildConfig.VERSION_NAME)
    // 页面入场：顶栏标题、图标版本行与三个入口卡依次弹入
    ProvideEntrance {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(title = stringResource(R.string.settings_about_app))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                // 内容自顶栏下方 60dp 开始，水平居中；超出屏幕时可滚动
                .padding(start = 16.dp, end = 16.dp, top = 60.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.staggeredEntrance(0),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // 应用图标取展示尺寸的一半（56dp）；资源本身为 256px 缩略图
                Image(
                    painter = painterResource(R.drawable.ic_about_app),
                    contentDescription = appLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(AppComponentShape),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = versionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            // 图标/版本行与下方条目之间：原 28dp 基础上再增加 30dp
            Spacer(Modifier.height(58.dp))
            // 复用设置页的单条入口卡（MiuixCard + ArrowPreference + 主题色图标）
            MiuixSettingsTheme {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SettingsEntryCard(
                        modifier = Modifier.staggeredEntrance(1),
                        title = stringResource(R.string.about_project_url),
                        iconRes = R.drawable.ic_update_github,
                        onClick = {
                            // 交给系统浏览器打开项目仓库；无可用浏览器时静默失败
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)))
                            }
                        },
                    )
                    // 点击行为待接入：暂为空实现，先保持条目正常外观
                    SettingsEntryCard(
                        modifier = Modifier.staggeredEntrance(2),
                        title = stringResource(R.string.about_acknowledgements),
                        iconRes = R.drawable.ic_about_acknowledgements,
                        onClick = {},
                    )
                    SettingsEntryCard(
                        modifier = Modifier.staggeredEntrance(3),
                        title = stringResource(R.string.about_disclaimer),
                        iconRes = R.drawable.ic_about_disclaimer,
                        onClick = { showDisclaimer = true },
                    )
                }
            }
        }
    }
    }

    if (showDisclaimer) {
        AppAlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = {
                Text(
                    stringResource(R.string.about_disclaimer),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            // 声明正文较长，允许正文区域滚动
            textScrollEnabled = true,
            text = {
                Text(
                    stringResource(R.string.about_disclaimer_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                DialogTextButton(
                    text = stringResource(R.string.common_confirm),
                    onClick = { showDisclaimer = false },
                )
            },
        )
    }
}
