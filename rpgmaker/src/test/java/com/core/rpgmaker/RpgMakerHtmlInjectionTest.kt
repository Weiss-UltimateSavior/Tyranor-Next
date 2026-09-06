package com.core.rpgmaker

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpgMakerHtmlInjectionTest {
    @Test
    fun injectsCompatibilityHookAndModResourcesBeforeBody() {
        val html = "<html><head></head><body><canvas></canvas></body></html>"
        val result = String(
            buildInjectedHtml(
                html,
                "window.compat=true;".toByteArray(),
                "<script src='/__tyranor__/mod.js'></script>",
                beforeBody = true,
            ),
            StandardCharsets.UTF_8,
        )

        assertTrue(result.indexOf("window.compat=true") < result.indexOf("/__tyranor__/mod.js"))
        assertTrue(result.indexOf("/__tyranor__/mod.js") < result.indexOf("</body>"))
    }

    @Test
    fun leavesDocumentUnchangedWhenNoInjectionIsConfigured() {
        val html = "<html><body>game</body></html>"
        assertEquals(html, String(buildInjectedHtml(html, ByteArray(0), "", true)))
    }

    @Test
    fun earlyHookLandsInHeadBeforeLateHookInBody() {
        // v1/v2 的 NW.js polyfill（earlyHook）必须先于游戏脚本（</body> 前的 lateHook）执行
        val html = "<html><head><title>t</title></head><body><canvas></canvas></body></html>"
        val result = String(
            buildInjectedHtmlTwoPhase(
                html,
                "window.__early=1;".toByteArray(),
                "window.__late=1;".toByteArray(),
                "",
                injectBeforeBody = true,
            ),
            StandardCharsets.UTF_8,
        )

        assertTrue(result.indexOf("window.__early=1;") < result.indexOf("</head>"))
        assertTrue(result.indexOf("</head>") < result.indexOf("window.__late=1;"))
        assertTrue(result.indexOf("window.__late=1;") < result.indexOf("</body>"))
    }

    @Test
    fun lateHookAppendsAtEndWhenBodyMarkerMissing() {
        // 页面无 </body>（压缩/精简 HTML）时 late 段必须尾插，
        // 保证 polyfill（early）仍先于引擎 hook（late）执行
        val html = "<html><head></head><canvas></canvas>"
        val result = String(
            buildInjectedHtmlTwoPhase(
                html,
                "window.__early=1;".toByteArray(),
                "window.__late=1;".toByteArray(),
                "",
                injectBeforeBody = true,
            ),
            StandardCharsets.UTF_8,
        )

        assertTrue(result.indexOf("window.__early=1;") < result.indexOf("window.__late=1;"))
        assertTrue(result.trimEnd().endsWith("</script>"))
        assertTrue(result.lastIndexOf("window.__late=1;") > result.lastIndexOf("</head>"))
    }

    @Test
    fun lenientUriDecodeKeepsLiteralPercentAndMultibyteSequences() {
        // 非法 % 序列保留原字符，合法 %XX 序列按字节解码后整体以 UTF-8 组装
        //（多字节序列不得解成 Latin-1 乱码）
        assertEquals("黑白_128%.png", decodeUriLenient("%E9%BB%91%E7%99%BD_128%.png"))
        assertEquals("plain.png", decodeUriLenient("plain.png"))
        assertEquals("trailing%.png", decodeUriLenient("trailing%.png"))
        assertEquals("bad%2z.png", decodeUriLenient("bad%2z.png"))
    }
}
