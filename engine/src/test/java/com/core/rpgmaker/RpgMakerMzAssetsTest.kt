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
        // isGameActive 必须在 managers hook 里：lateHook 求值时 SceneManager 还没定义，
        // 写在这里的分支永远不执行。断言「不得作为可执行赋值出现」能挡住这个错误
        // （注释里提到它是允许的，那只是说明为什么搬走）。
        assertFalse(
            "lateHook 求值时 SceneManager 尚未定义，isGameActive 不能在这里赋值",
            Regex("""SceneManager\.isGameActive\s*=""").containsMatchIn(hook),
        )
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

    /**
     * 旧的三件套仍被 v0 路径（TyranoActivity）使用，删了会破坏 Tyranor-2.3.4 的 MZ 支持。
     *
     * 用内容哈希而不是「存在且非空」：v0 的运行时行为要在本次改动后**逐字节不变**，
     * 一行语义改动同样会破坏这条不变量，只查存在性抓不到。
     */
    @Test
    fun legacyV0MzAssetsAreUnchanged() {
        // 内容哈希锁住「逐字节不变」：只查存在性抓不到一行语义改动
        val expected = mapOf(
            "__rmmz__.js" to "a9015aa80b6eafbe98296511b713e355e2178a46e3744c2dedd9402d320305bb",
            "__hook_rmmz_core.js" to "f2fd3cab09262f3222beb430b2f30e040a6f361800fb22b034b34ad92cdc433c",
            "__hook_rmmz_managers.js" to "a56262fac79a1fb4ffdd3e3e3adef00cf2212dd59e25a29ca473c44b27279171",
        )
        for ((name, sha256) in expected) {
            val file = File("src/main/assets/$name")
            assertTrue("$name 仍被 v0 路径引用，不得删除", file.isFile)
            assertEquals(
                "$name 是 v0（Tyranor-2.3.4）的运行时资产，本次改动必须逐字节不变；" +
                    "若确需修改，要同时在 TyranoActivity 侧验收并更新这里的基准",
                sha256,
                sha256Of(file),
            )
        }
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}