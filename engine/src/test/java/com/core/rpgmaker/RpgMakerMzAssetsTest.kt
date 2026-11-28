package com.core.rpgmaker

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MZ v2 会话的注入资产（`assets/rpgmaker/mz/`）。
 *
 * 这里守住的是**资产本身**的契约，而不是拼接顺序（后者由 `RpgMakerHtmlInjectionTest`
 * 覆盖）：这些文件是 JS，Kotlin 侧不会编译它们，删名字/写错时机不会有任何编译错误，
 * 只会在真机上静默失效。
 */
class RpgMakerMzAssetsTest {

    private fun asset(name: String): String {
        val file = File("src/main/assets/$name")
        assertTrue("$name 必须存在（engine 模块工作目录是 engine/）", file.isFile)
        return file.readText(StandardCharsets.UTF_8)
    }

    @Test
    fun mzHookIsAPlainScriptThatDoesNotAssumeCoreScriptIsLoaded() {
        val hook = asset("rpgmaker/mz/__mz_hook.js")
        // MZ 的 index.html 只直接引用 js/main.js：lateHook 求值时 Graphics 还不存在
        assertFalse(
            "顶层不得出现无保护的 Graphics 读取（会在解析期抛 ReferenceError）",
            Regex("""^\s*Graphics\.""", RegexOption.MULTILINE).containsMatchIn(hook),
        )
        assertTrue("必须等 Graphics 出现再覆写", hook.contains("setTimeout"))
        assertTrue("重试必须有上限", hook.contains("maxAttempts"))
        assertTrue("必须带能力探测", hook.contains("typeof g._createEffekseerContext"))
        assertTrue("必须保留 v0 hook 里的 overflow 处理", hook.contains("overflow"))
        assertTrue("必须保留 isGameActive 覆写", hook.contains("isGameActive"))
        // 只在会话明确要求时装桩
        assertTrue(hook.contains("cfg.effekseer === false"))
    }

    @Test
    fun mzCoreHookPinsStreamingDecodeButRequiresVorbisDecoder() {
        val hook = asset("rpgmaker/mz/__mz_core_hook.js")
        assertTrue(hook.contains("_shouldUseDecoder"))
        assertTrue("缺 VorbisDecoder 时必须保持原实现，否则 BGM 全哑", hook.contains("VorbisDecoder"))
        assertTrue("必须带能力探测", hook.contains("typeof"))
        // MZ 的 Graphics._stretchHeight 原实现按 isMobileDevice 取 clientHeight * 0.9；
        // 我们要铺满，不能在移动端留黑边
        assertTrue("必须保留 _stretchHeight 的铺满覆写", hook.contains("_stretchHeight"))
    }

    @Test
    fun mzManagersHookKeepsTheSaveBridgeContract() {
        val hook = asset("rpgmaker/mz/__mz_managers_hook.js")
        // 引擎侧存档方式**不动**（明文 JSON + saveDataManager 桥），见实施方案阶段 B
        listOf("window.saveDataManager.Save", "window.saveDataManager.Load", "window.saveDataManager.Exists").forEach {
            assertTrue("必须走 $it", hook.contains(it))
        }
        assertTrue("必须保留 SceneManager.isGameActive", hook.contains("isGameActive"))
        // 桥是页面级全局：不能缓存到闭包变量里——页面重载后旧闭包会指向上一份桥
        assertFalse("不得把桥缓存进闭包", Regex("""var bridge = window\.saveDataManager""").containsMatchIn(hook))
    }

    /** 旧的三件套仍被 v0 路径（TyranoActivity）使用，删了会破坏 Tyranor-2.3.4 的 MZ 支持。 */
    @Test
    fun legacyV0MzAssetsAreStillPresent() {
        listOf("__rmmz__.js", "__hook_rmmz_core.js", "__hook_rmmz_managers.js").forEach { name ->
            val file = File("src/main/assets/$name")
            assertTrue("$name 仍被 v0 路径引用，不得删除", file.isFile)
            assertTrue("$name 不应为空", file.length() > 0)
        }
    }
}