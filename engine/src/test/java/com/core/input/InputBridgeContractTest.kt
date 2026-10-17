package com.core.input

import android.view.KeyEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨边界字面量锚定：Kotlin 侧的桥名 / 接收器名与 inject 进页面的 JS 资产必须同名。
 *
 * 两侧字面量此前各写一份（`InputRemapController.BRIDGE_NAME` 与 `__rpgmaker_mod_ui.js`
 * 里的 `window.TyranorInputNative`），任一侧改名都只表现为「点了没反应」的静默失效。
 * 与 `GameOverridePartitionsTest` 的三处字面量锚定同一约定。
 *
 * 资产路径按 Gradle 单测默认工作目录（模块根）解析；解析不到即失败，避免静默跳过。
 */
class InputBridgeContractTest {

    private fun asset(name: String): String {
        val file = File("src/main/assets", name)
        assertTrue("资产缺失（单测工作目录应为 engine 模块根）：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun bridgeNameIsAnchoredToTheModUiAsset() {
        // 改动 BRIDGE_NAME 等于改 WebView 上的注册名（协议变更），必须同步改 JS 资产；
        // 这里把「当前协议」写死，避免两侧一起漂移而测试仍然通过
        assertEquals("TyranorInputNative", InputRemapController.BRIDGE_NAME)

        val ui = asset("__rpgmaker_mod_ui.js")
        assertTrue(
            "修改器 UI 必须引用桥名 ${InputRemapController.BRIDGE_NAME}",
            ui.contains(InputRemapController.BRIDGE_NAME),
        )
        listOf("isAvailable", "enterEdit", "resetToDefaults").forEach { method ->
            assertTrue("修改器 UI 必须调用 $method()", ui.contains(method))
        }
    }

    @Test
    fun webSinkDispatchesToTheReceiverDefinedByTheInjectedAsset() {
        val receiver = "window.__tyranorInput"
        val receiverAsset = asset("__tyranor_input.js")
        assertTrue("接收器资产必须定义 $receiver", receiverAsset.contains(receiver))
        listOf("key:", "mouse:", "cancel:").forEach { member ->
            assertTrue("接收器资产必须暴露 $member", receiverAsset.contains(member))
        }

        // 真实走一遍出口：产出的 JS 必须调用同一个接收器名（改名即在此失败）
        val dispatched = ArrayList<String>()
        val sink = WebInputSink({ js -> dispatched.add(js) })
        sink.send(KeyEvent.KEYCODE_ENTER, true)
        sink.send(CanonicalKeys.MOUSE_LEFT, true)
        sink.releaseAll()

        // keydown + 鼠标键 + releaseAll 的 keyup + cancel()
        assertEquals(4, dispatched.size)
        dispatched.forEach { js ->
            assertTrue("派发脚本必须调用 $receiver：$js", js.contains(receiver))
        }
    }
}
