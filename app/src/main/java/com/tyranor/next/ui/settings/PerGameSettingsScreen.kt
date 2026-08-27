package com.tyranor.next.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tyranor.next.R
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.EngineSettingsStore
import com.tyranor.next.core.settings.PerGameSettingsStore
import com.tyranor.next.theme.MiuixSettingsTheme
import com.tyranor.next.ui.common.AppAlertDialog
import com.tyranor.next.ui.common.TopBarIcon
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 单游戏（应用级）引擎设置页。每项基于「覆盖 ?: 全局」，可单独切回“跟随全局”。
 * 顶部右侧保存图标提交覆盖；左上返回。
 */
@Composable
fun PerGameSettingsScreen(game: ScanGame) {
    val ctx = LocalContext.current
    val gid = game.uri

    // 覆盖值（null=跟随全局）
    var krVersion by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ENGINE_VERSION)) }
    var krKernel by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ENGINE_KERNEL)) }
    var krScoped by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_SCOPED_SAVE_DIR)) }
    var krFont by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_DEFAULT_FONT)) }
    var krForceFont by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_FORCE_DEFAULT_FONT)) }
    val krRender = PerGameSettingsStore.KR_FIELDS.associateWith { field ->
        remember(field) { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, field)) }
    }

    var artVersion by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_VERSION)) }
    var artRotate by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_ART_ROTATE)) }
    var artPatch by remember { mutableStateOf(PerGameSettingsStore.getStr(ctx, gid, PerGameSettingsStore.F_ART_PATCH)) }

    val onsOverride = remember { mutableStateOf(PerGameSettingsStore.loadOnsOverride(ctx, gid) ?: JSONObject()) }
    var onsScoped by remember { mutableStateOf(onsBool(onsOverride.value, "scopedsavedir")) }
    var onsStretch by remember { mutableStateOf(onsBool(onsOverride.value, "strechfull")) }
    var onsCutout by remember { mutableStateOf(onsBool(onsOverride.value, "ignorecutout")) }
    var onsNoVideo by remember { mutableStateOf(onsBool(onsOverride.value, "disablevideo")) }
    var onsSharp by remember { mutableStateOf(onsBool(onsOverride.value, "sharpness")) }
    var onsSharpVal by remember { mutableStateOf(onsStr(onsOverride.value, "sharpness_value", "2")) }
    var onsEnc by remember { mutableStateOf(onsStr(onsOverride.value, "encoding", "gbk")) }

    var tyExternal by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, "ty_external")) }
    var tyScoped by remember { mutableStateOf(PerGameSettingsStore.getBool(ctx, gid, "ty_scoped")) }
    var rpgMakerMod by remember {
        mutableStateOf(
            PerGameSettingsStore.getBool(ctx, gid, PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED),
        )
    }

    val fontLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val p = copyFontToPrivate(ctx, uri)
            if (p != null) krFont = p
        }
    }

    val globalKrVersion = EngineSettingsStore.getKrEngineVersion(ctx)
    val globalKrKernel = EngineSettingsStore.getKrKernel(ctx)
    val globalKrScoped = EngineSettingsStore.isKrScopedSaveDir(ctx)
    val globalKrFont = EngineSettingsStore.getKrDefaultFont(ctx)
    val globalForce = EngineSettingsStore.isKrForceDefaultFont(ctx)
    val configuredGlobalRenderer = EngineSettingsStore.getKrRenderer(ctx)
    val globalOns = remember { EngineSettingsStore.loadOns(ctx) }
    val globalArtVersion = EngineSettingsStore.getArtEngineVersion(ctx)
    val globalArtRotate = EngineSettingsStore.isArtRotateScreen(ctx)
    val globalArtPatch = EngineSettingsStore.getArtAutoPatch(ctx)
    val globalTyExternal = EngineSettingsStore.isTyranoExternalNetwork(ctx)
    val globalTyScoped = EngineSettingsStore.isTyranoScopedSaveDir(ctx)
    val globalRpgMakerMod = EngineSettingsStore.isRpgMakerModEnabled(ctx)

    val isSdl3 = (krKernel ?: globalKrKernel) == EngineSettingsStore.KERNEL_KRKRSDL3
    val globalRenderer = configuredGlobalRenderer.ifEmpty {
        if (isSdl3) EngineSettingsStore.RENDERER_OPENGL else ""
    }
    val effVersion = krVersion ?: globalKrVersion
    val krIs134126 = effVersion == EngineSettingsStore.KR_134 || effVersion == EngineSettingsStore.KR_126

    // 渲染相关全局值（跟随全局时展示用）
    val globalAccurate = EngineSettingsStore.getKrOglAccurateRender(ctx) == "1"
    val globalMem = EngineSettingsStore.getKrMemUsage(ctx)
    val globalDrawThread = EngineSettingsStore.getKrSoftwareDrawThread(ctx)
    val globalSwCompress = EngineSettingsStore.getKrSoftwareCompressTex(ctx)
    val globalOglCompress = EngineSettingsStore.getKrOglCompressTex(ctx)
    val globalTexsize = EngineSettingsStore.getKrOglMaxTexsize(ctx)
    val globalFps = EngineSettingsStore.getKrFpsLimit(ctx)
    val effRenderer = krRender[PerGameSettingsStore.F_RENDERER]!!.value ?: globalRenderer

    fun save() {
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ENGINE_VERSION, krVersion)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ENGINE_KERNEL, krKernel)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_SCOPED_SAVE_DIR, krScoped)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_DEFAULT_FONT, krFont)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_FORCE_DEFAULT_FONT, krForceFont)
        krRender.forEach { (field, st) -> PerGameSettingsStore.setStr(ctx, gid, field, st.value) }
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_VERSION, artVersion)
        PerGameSettingsStore.setBool(ctx, gid, PerGameSettingsStore.F_ART_ROTATE, artRotate)
        PerGameSettingsStore.setStr(ctx, gid, PerGameSettingsStore.F_ART_PATCH, artPatch)
        val onsObj = JSONObject()
        putIfNotNull(onsObj, "scopedsavedir", onsScoped)
        putIfNotNull(onsObj, "strechfull", onsStretch)
        putIfNotNull(onsObj, "ignorecutout", onsCutout)
        putIfNotNull(onsObj, "disablevideo", onsNoVideo)
        putIfNotNull(onsObj, "sharpness", onsSharp)
        putIfNotNull(onsObj, "sharpness_value", onsSharpVal)
        putIfNotNull(onsObj, "encoding", onsEnc)
        PerGameSettingsStore.setOnsOverride(ctx, gid, onsObj)
        PerGameSettingsStore.setBool(ctx, gid, "ty_external", tyExternal)
        PerGameSettingsStore.setBool(ctx, gid, "ty_scoped", tyScoped)
        PerGameSettingsStore.setBool(
            ctx,
            gid,
            PerGameSettingsStore.F_RPG_MAKER_MOD_ENABLED,
            rpgMakerMod,
        )
    }

    MiuixSettingsTheme {
        MiuixScaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0.dp),
            topBar = {
                Column(modifier = Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background)) {
                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(game.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TopBarIcon(painterResource(R.drawable.ic_save), "保存", MiuixTheme.colorScheme.primary) {
                                save()
                                android.widget.Toast.makeText(ctx, "已保存", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding() + 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (game.engine) {
                    EngineType.KIRIKIRI -> {
                        item {
                            SectionCard("KRKR") {
                                OverrideSwitch("独立存档目录", globalKrScoped, krScoped) { krScoped = it }
                                OverrideChoice("引擎版本", KR_VERSION_MAP2, globalKrVersion, krVersion) { krVersion = it }
                                OverrideChoice("引擎内核", KR_KERNEL_MAP2, globalKrKernel, krKernel) { krKernel = it }
                            }
                        }
                        item {
                            SectionCard("渲染") {
                                if (!isSdl3) {
                                    OverrideSwitch("OpenGL 精确渲染", globalAccurate, krRender[PerGameSettingsStore.F_OGL_ACCURATE_RENDER]!!.value == "1") { b ->
                                        krRender[PerGameSettingsStore.F_OGL_ACCURATE_RENDER]!!.value = when (b) { null -> ""; true -> "1"; false -> "0" }
                                    }
                                    OverrideChoice("内存用量", KR_MEM_MAP2, globalMem, krRender[PerGameSettingsStore.F_MEM_USAGE]!!.value, emptyLabel = "引擎默认") {
                                        krRender[PerGameSettingsStore.F_MEM_USAGE]!!.value = it
                                    }
                                }
                                OverrideChoice("渲染器", KR_RENDERER_MAP2, globalRenderer, krRender[PerGameSettingsStore.F_RENDERER]!!.value, emptyLabel = "引擎默认") {
                                    krRender[PerGameSettingsStore.F_RENDERER]!!.value = it
                                }
                                if (!isSdl3) {
                                    if (effRenderer == "" || effRenderer == EngineSettingsStore.RENDERER_SOFTWARE) {
                                        OverrideChoice("软件渲染线程数", KR_THREAD_MAP2, globalDrawThread, krRender[PerGameSettingsStore.F_SOFTWARE_DRAW_THREAD]!!.value, emptyLabel = "自动") {
                                            krRender[PerGameSettingsStore.F_SOFTWARE_DRAW_THREAD]!!.value = it
                                        }
                                        OverrideChoice("软件纹理压缩", KR_SW_COMPRESS_MAP2, globalSwCompress, krRender[PerGameSettingsStore.F_SOFTWARE_COMPRESS_TEX]!!.value, emptyLabel = "引擎默认") {
                                            krRender[PerGameSettingsStore.F_SOFTWARE_COMPRESS_TEX]!!.value = it
                                        }
                                    }
                                    if (!krIs134126) {
                                        OverrideChoice("FPS 限制", KR_FPS_MAP2, globalFps, krRender[PerGameSettingsStore.F_FPS_LIMIT]!!.value, emptyLabel = "引擎默认") {
                                            krRender[PerGameSettingsStore.F_FPS_LIMIT]!!.value = it
                                        }
                                    }
                                    if (effRenderer == "" || effRenderer == EngineSettingsStore.RENDERER_OPENGL) {
                                        OverrideChoice("OpenGL 纹理压缩", KR_OGL_COMPRESS_MAP2, globalOglCompress, krRender[PerGameSettingsStore.F_OGL_COMPRESS_TEX]!!.value, emptyLabel = "引擎默认") {
                                            krRender[PerGameSettingsStore.F_OGL_COMPRESS_TEX]!!.value = it
                                        }
                                        OverrideChoice("最大纹理尺寸", KR_TEXSIZE_MAP2, globalTexsize, krRender[PerGameSettingsStore.F_OGL_MAX_TEXSIZE]!!.value, emptyLabel = "自动") {
                                            krRender[PerGameSettingsStore.F_OGL_MAX_TEXSIZE]!!.value = it
                                        }
                                    }
                                }
                            }
                        }
                        if (!isSdl3) {
                            item {
                                SectionCard("字体") {
                                    OverrideFont("默认字体", globalKrFont, krFont, onReset = { krFont = "" }, onPick = { fontLauncher.launch("*/*") })
                                    if (effVersion != EngineSettingsStore.KR_126) {
                                        OverrideSwitch("强制默认字体", globalForce, krForceFont) { krForceFont = it }
                                    }
                                }
                            }
                        }
                    }
                    EngineType.ONS -> item {
                        SectionCard("ONS") {
                            OverrideSwitch("独立存档目录", globalOns.scopedSaveDir, onsScoped) { onsScoped = it }
                            OverrideSwitch("全屏拉伸", globalOns.stretchFull, onsStretch) { onsStretch = it }
                            OverrideSwitch("忽略刘海", globalOns.ignoreCutout, onsCutout) { onsCutout = it }
                            OverrideSwitch("禁用视频", globalOns.disableVideo, onsNoVideo) { onsNoVideo = it }
                            OverrideSwitch("画面锐化", globalOns.sharpness, onsSharp) { onsSharp = it }
                            OverrideChoice("文本编码", ONS_ENCODING_MAP2, globalOns.encoding.decode(), onsEnc) { onsEnc = it }
                        }
                    }
                    EngineType.ARTEMIS -> item {
                        SectionCard("Artemis") {
                            OverrideChoice("引擎版本", ART_VERSION_MAP2, globalArtVersion, artVersion) { artVersion = it }
                            OverrideSwitch("画面反转", globalArtRotate, artRotate) { artRotate = it }
                            OverrideChoice("自动补丁", ART_PATCH_MAP2, globalArtPatch, artPatch) { artPatch = it }
                        }
                    }
                    EngineType.RENPY -> item {
                        SectionCard("Ren'Py") {
                            Text(
                                "Ren'Py 使用外置 APK 引擎模块，模块安装后默认启用；当前没有可由主应用覆盖的单游戏设置。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            )
                        }
                    }
                    EngineType.TYRANO,
                    EngineType.RPG_MV,
                    EngineType.RPG_MZ,
                    EngineType.VN,
                    EngineType.WEB_OTHER,
                    EngineType.UNKNOWN -> item {
                        SectionCard(if (game.engine == EngineType.UNKNOWN) "Web" else game.engine.displayName) {
                            OverrideSwitch("允许外部网络", globalTyExternal, tyExternal) { tyExternal = it }
                            if (game.engine !in setOf(EngineType.VN, EngineType.WEB_OTHER)) {
                                OverrideSwitch("独立存档目录", globalTyScoped, tyScoped) { tyScoped = it }
                            }
                            if (game.engine == EngineType.RPG_MV || game.engine == EngineType.RPG_MZ) {
                                OverrideSwitch("游戏修改器", globalRpgMakerMod, rpgMakerMod) { rpgMakerMod = it }
                            }
                        }
                    }
                }

                item { Box(Modifier.fillMaxWidth().navigationBarsPadding().height(12.dp)) }
            }
        }
    }
}

// ───────────────────────── 覆盖行组件 ─────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    MiuixCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 8.dp,
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** 覆盖版下拉行：Miuix OverlayDropdownPreference，选项首位为“跟随全局”。 */
@Composable
private fun OverrideChoice(label: String, options: Map<String, String>, global: String, override: String?, emptyLabel: String = "内置字体", onSet: (String?) -> Unit) {
    val following = override == null
    val effValue = override ?: global
    val keys = options.keys.toList()
    val labels = listOf("跟随全局 · ${labelOf(effValue, options, emptyLabel)}") + keys.map { options[it] ?: it }
    val index = if (following) 0 else (keys.indexOf(override).takeIf { it >= 0 } ?: -1) + 1
    OverlayDropdownPreference(
        title = label,
        items = labels,
        selectedIndex = index,
        onSelectedIndexChange = { i -> onSet(if (i == 0) null else keys[i - 1]) },
    )
}

/** 覆盖版开关行：Miuix OverlayDropdownPreference，三态（跟随全局 / 开 / 关）。 */
@Composable
private fun OverrideSwitch(label: String, global: Boolean, override: Boolean?, onSet: (Boolean?) -> Unit) {
    val labels = listOf("跟随全局（${if (global) "开" else "关"}）", "开", "关")
    val index = when { override == null -> 0; override -> 1; else -> 2 }
    OverlayDropdownPreference(
        title = label,
        items = labels,
        selectedIndex = index,
        onSelectedIndexChange = { i -> onSet(if (i == 0) null else i == 1) },
    )
}

/** 覆盖版字体行：Miuix ArrowPreference，点击弹窗选择（跟随全局 / 选择字体文件）。 */
@Composable
private fun OverrideFont(label: String, global: String, override: String?, onReset: () -> Unit, onPick: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val following = override == null
    val summary = if (following) "跟随全局（${global.ifEmpty { "内置字体" }}）" else override.ifEmpty { "内置字体" }
    ArrowPreference(title = label, summary = summary, onClick = { open = true })
    if (open) {
        AppAlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { onReset(); open = false }.padding(vertical = 8.dp)) { Text("跟随全局", style = MaterialTheme.typography.bodyMedium) }
                    Row(Modifier.fillMaxWidth().clickable { open = false; onPick() }.padding(vertical = 8.dp)) { Text("选择字体文件…", style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        )
    }
}

private fun labelOf(v: String, map: Map<String, String>, emptyLabel: String): String = map[v] ?: v.ifEmpty { emptyLabel }

private fun copyFontToPrivate(ctx: android.content.Context, uri: android.net.Uri): String? = try {
    val name = (uri.lastPathSegment ?: "font.ttf").substringAfterLast('/').substringAfterLast('\\')
    val dir = java.io.File(ctx.filesDir, "fonts")
    if (!dir.isDirectory && !dir.mkdirs()) return null
    val target = java.io.File(dir, name)
    ctx.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out -> input.copyTo(out) } } ?: return null
    target.absolutePath
} catch (t: Throwable) { null }

private fun onsBool(o: JSONObject, key: String): Boolean? = if (o.has(key)) o.optBoolean(key) else null
private fun onsStr(o: JSONObject, key: String, def: String): String? = if (o.has(key)) o.optString(key, def) else null
private fun putIfNotNull(o: JSONObject, key: String, v: Boolean?) { if (v != null) o.put(key, v) else o.remove(key) }
private fun putIfNotNull(o: JSONObject, key: String, v: String?) { if (v != null) o.put(key, v) else o.remove(key) }
private fun String.decode(): String = if (this == "sjis") "sjis" else if (this == "utf8") "utf8" else "gbk"

private val KR_VERSION_MAP2 = mapOf(
    EngineSettingsStore.KR_AUTO to "自动",
    EngineSettingsStore.KR_139 to "1.3.9",
    EngineSettingsStore.KR_134 to "1.3.4",
    EngineSettingsStore.KR_126 to "1.2.6",
)
private val KR_KERNEL_MAP2 = mapOf(
    EngineSettingsStore.KR_AUTO to "自动",
    EngineSettingsStore.KERNEL_KIRIKIRI2 to "吉里吉里2",
    EngineSettingsStore.KERNEL_KRKRSDL3 to "krkrsdl3",
)
private val KR_RENDERER_MAP2 = mapOf(
    EngineSettingsStore.RENDERER_SOFTWARE to "软件渲染",
    EngineSettingsStore.RENDERER_OPENGL to "OpenGL",
)
private val KR_THREAD_MAP2 = mapOf("0" to "自动") + (1..8).associate { it.toString() to "$it 线程" }
private val KR_SW_COMPRESS_MAP2 = mapOf(
    "none" to "无", "halfline" to "半行", "lz4" to "LZ4", "lz4+tlg5" to "LZ4+TLG5",
)
private val KR_OGL_COMPRESS_MAP2 = mapOf(
    "none" to "无", "half" to "半精度", "etc2" to "ETC2", "pvrtc" to "PVRTC",
)
private val KR_MEM_MAP2 = mapOf(
    EngineSettingsStore.MEM_USAGE_UNLIMITED to "不限制",
    EngineSettingsStore.MEM_USAGE_HIGH to "高",
    EngineSettingsStore.MEM_USAGE_MEDIUM to "中",
    EngineSettingsStore.MEM_USAGE_LOW to "低",
)
private val KR_TEXSIZE_MAP2 = mapOf("0" to "自动") + listOf(1024, 2048, 4096, 8192, 16384).associate { it.toString() to it.toString() }
private val KR_FPS_MAP2 = mapOf("60" to "60", "45" to "45", "30" to "30", "15" to "15")
private val ONS_ENCODING_MAP2 = mapOf("gbk" to "GBK", "sjis" to "Shift-JIS", "utf8" to "UTF-8")
private val ART_VERSION_MAP2 = mapOf(
    EngineSettingsStore.ART_ENGINE_AUTO to "自动",
    EngineSettingsStore.ART_ENGINE_V1 to "V1",
    EngineSettingsStore.ART_ENGINE_V2 to "V2",
    EngineSettingsStore.ART_ENGINE_V3 to "V3",
)
private val ART_PATCH_MAP2 = mapOf(
    EngineSettingsStore.AUTO_PATCH_ASK to "启动时询问",
    EngineSettingsStore.AUTO_PATCH_AUTO to "自动",
    EngineSettingsStore.AUTO_PATCH_OFF to "关闭",
)
