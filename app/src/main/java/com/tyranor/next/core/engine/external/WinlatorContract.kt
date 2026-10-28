package com.tyranor.next.core.engine.external

import com.tyranor.next.core.settings.EngineSettingsStore
import org.json.JSONObject

/**
 * Winlator（winlator-cn）外置启动协议常量（唯一字面量来源）。
 *
 * 协议来源：winlator-cn `docs/external-launch.md` 与 `ExternalLaunchActivity`。
 * 入口为导出的 `com.winlator.ExternalLaunchActivity`，安装包名与 Java 包名同为 `com.winlator`
 * （与 `/Users/weiss/github- engine/winlator-cn` 的 applicationId 一致）；`exe_path` 支持相对
 * `dir_path` 的文件名，未映射的 `dir_path` 由 Winlator 从 `W:` 递减分配空闲盘符**临时挂载**
 * （`save=false` 不落盘）。
 */
object WinlatorContract {

    /** 安装包名（applicationId）。 */
    const val PACKAGE_NAME = "com.winlator"
    const val ACTIVITY_NAME = "com.winlator.ExternalLaunchActivity"
    /** 待挂载并启动的目录（Unix 绝对路径）。 */
    const val EXTRA_DIR_PATH = "dir_path"

    /** 可执行文件：绝对路径（DOS/Unix/file://）或相对 [EXTRA_DIR_PATH] 的文件名。 */
    const val EXTRA_EXE_PATH = "exe_path"

    /** 已存在的 Winlator 快捷方式（`.desktop`）绝对路径；与 `dir_path`/`exe_path` 互斥。 */
    const val EXTRA_SHORTCUT_PATH = "shortcut_path"

    /** 是否弹 Winlator 侧确认框（默认 true；`false` 仅在对方全局设置允许时生效）。 */
    const val EXTRA_CONFIRM = "confirm"

    /** 调用方自定义标识：用于日志与会话内防抖。 */
    const val EXTRA_LAUNCH_ID = "launch_id"

    /** 目标容器 id（0 = 不指定，走 Winlator 回退链）。 */
    const val EXTRA_CONTAINER_ID = "container_id"

    /** 目标容器名（大小写不敏感精确匹配，支持中文；容器 id 优先）。 */
    const val EXTRA_CONTAINER_NAME = "container_name"

    /** 图形驱动（如 `turnip`、`turnip,zink`；等价 overrides `graphicsDriver`）。 */
    const val EXTRA_GRAPHICS_DRIVER = "graphics_driver"

    /** 图形加速 `dxvk` / `wined3d`。 */
    const val EXTRA_DXWRAPPER = "dxwrapper"

    /** 分辨率（如 `1600x900`，≥320x160）。 */
    const val EXTRA_SCREEN_SIZE = "screen_size"

    /** 客机语言环境（LC_ALL，如 `ja_JP.UTF-8`）。 */
    const val EXTRA_LC_ALL = "lc_all"

    /** 客机时区（TZ，如 `Asia/Tokyo`）。 */
    const val EXTRA_TZ = "tz"

    /** 批量覆盖配置（JSON 对象字符串，白名单见指南 §5）。 */
    const val EXTRA_OVERRIDES = "overrides"

    /** `true` 时把本次覆盖写入容器配置（静默持久化）。 */
    const val EXTRA_SAVE = "save"

    /** overrides 中由本 App 下发的键。 */
    const val OVERRIDE_BOX64_PRESET = "box64Preset"

    /** overrides 中的 Win Components（内置 Wine / 外置 Windows）紧凑串。 */
    const val OVERRIDE_WINCOMPONENTS = "wincomponents"

    /** overrides 中的音频驱动（alsa / pulseaudio）。 */
    const val OVERRIDE_AUDIO_DRIVER = "audioDriver"

    /** overrides 中的自定义环境变量（空格分隔 K=V）。 */
    const val OVERRIDE_ENV_VARS = "envVars"

    /** overrides 中的 DX wrapper 配置（WineD3D 的 `renderer=...` 走这里）。 */
    const val OVERRIDE_DXWRAPPER_CONFIG = "dxwrapperConfig"

    /**
     * 旧版 App 内部预设（仅作为**旧存储值**读入，不再作为下发值写出）。
     * 读到时归一回 `dxwrapper="wined3d"` + `wined3dRenderer=gl|gdi`。
     */
    const val DXWRAPPER_WINED3D_GL = "wined3d_gl"
    const val DXWRAPPER_WINED3D_GDI = "wined3d_gdi"

    /** WineD3D 的协议标识：配合 [OVERRIDE_DXWRAPPER_CONFIG] 的 `renderer` 下发。 */
    const val DXWRAPPER_WINED3D = "wined3d"

    /**
     * 引擎设置解析出的下发参数；空值（0/空串/false）一律不下发。
     */
    data class LaunchOptions(
        val containerId: Int = 0,
        val containerName: String = "",
        val graphicsDriver: String = "",
        val dxwrapper: String = "",
        val wined3dRenderer: String = "",
        val screenSize: String = "",
        val lcAll: String = "",
        val tz: String = "",
        val box64Preset: String = "",
        val winComponents: String = "",
        val audioDriver: String = "",
        val envVars: String = "",
        val save: Boolean = false,
    )

    /**
     * 设置项 → Intent extras 的纯函数映射（不含 `dir_path`/`exe_path`/`confirm`/`launch_id`）。
     * 值为 Int/Boolean 时按原类型传入，其余为 String；用于单测覆盖下发规则。
     * WineD3D 渲染器只在 `dxwrapper == wined3d` 且 renderer 非空时下发，
     * 并与 Box64 预设、Win Components、音频、环境变量共用一个 overrides 对象；
     * save 为 true 时由接收端持久化覆盖值。
     */
    fun extras(options: LaunchOptions): Map<String, Any> = buildMap {
        if (options.containerId > 0) put(EXTRA_CONTAINER_ID, options.containerId)
        options.containerName.trim().takeIf { it.isNotEmpty() }?.let { put(EXTRA_CONTAINER_NAME, it) }
        options.graphicsDriver.trim().takeIf { it.isNotEmpty() }?.let { put(EXTRA_GRAPHICS_DRIVER, it) }
        val wrapper = options.dxwrapper.trim()
        wrapper.takeIf { it.isNotEmpty() }?.let { put(EXTRA_DXWRAPPER, it) }
        options.screenSize.trim().takeIf { it.isNotEmpty() }?.let { put(EXTRA_SCREEN_SIZE, it) }
        options.lcAll.trim().takeIf { it.isNotEmpty() }?.let { put(EXTRA_LC_ALL, it) }
        options.tz.trim().takeIf { it.isNotEmpty() }?.let { put(EXTRA_TZ, it) }
        val overrides = JSONObject()
        options.box64Preset.trim().takeIf { it.isNotEmpty() }?.let {
            overrides.put(OVERRIDE_BOX64_PRESET, it)
        }
        options.winComponents.trim().takeIf { it.isNotEmpty() }?.let {
            // winlator-cn 按位置比较组件串：部分串会错位并污染缓存，只在九项齐全时下发。
            if (EngineSettingsStore.isCompleteWinlatorWinComponents(it)) {
                overrides.put(OVERRIDE_WINCOMPONENTS, it)
            }
        }
        options.audioDriver.trim().takeIf { it.isNotEmpty() }?.let {
            overrides.put(OVERRIDE_AUDIO_DRIVER, it)
        }
        options.envVars.trim().takeIf { it.isNotEmpty() }?.let {
            overrides.put(OVERRIDE_ENV_VARS, it)
        }
        if (wrapper == DXWRAPPER_WINED3D) {
            options.wined3dRenderer.trim().takeIf { it.isNotEmpty() }?.let {
                // WineD3D 走首个配置槽，整项替换容器里的 WineD3D 渲染配置。
                overrides.put(OVERRIDE_DXWRAPPER_CONFIG, "renderer=$it")
            }
        }
        if (overrides.length() > 0) put(EXTRA_OVERRIDES, overrides.toString())
        // 接收端持久化 envVars 时是整体替换容器变量（不是追加），会和界面文案相反地丢配置；
        // 在接收端改为按 key 合并前，带 envVars 的启动不写 save。
        if (options.save && options.envVars.trim().isEmpty()) put(EXTRA_SAVE, true)
    }
}
