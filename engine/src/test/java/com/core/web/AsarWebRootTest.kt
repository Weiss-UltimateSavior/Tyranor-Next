package com.core.web

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * asar 网页根前缀探测的单一实现契约。
 *
 * 该探测此前在三处各写一份（两个服务器 + RPG Maker 的 fs 桥），其中 fs 桥只认
 * `""` 与 `www/`，导致 `app/`、`resources/app/` 布局的 asar 出现
 * 「HTTP 服务器读得到、fs 桥读不到」的分裂行为。本测试锁定四种布局，防止再次漂移。
 */
class AsarWebRootTest {

    private fun prefixFor(vararg entries: String): String {
        val set = entries.toSet()
        return AsarWebRoot.prefixFor { it in set }
    }

    @Test
    fun recognisesAllFourSupportedLayouts() {
        assertEquals("归档根布局", "", prefixFor("index.html", "js/rpg_core.js"))
        assertEquals("www 布局（MV 常见）", "www/", prefixFor("www/index.html"))
        assertEquals("app 布局", "app/", prefixFor("app/index.html"))
        assertEquals("resources/app 布局（Electron 常见）", "resources/app/", prefixFor("resources/app/index.html"))
    }

    @Test
    fun firstMatchWinsWhenMultipleLayoutsPresent() {
        // 归档根优先（与重构前的 when 分支顺序一致）
        assertEquals("", prefixFor("index.html", "www/index.html", "app/index.html"))
        assertEquals("www/", prefixFor("www/index.html", "app/index.html"))
        assertEquals("app/", prefixFor("app/index.html", "resources/app/index.html"))
    }

    @Test
    fun returnsEmptyWhenNoLayoutMatches() {
        // 认不出布局时回退归档根（与重构前的 else 分支一致），而不是抛错中断启动
        assertEquals("", prefixFor("js/rpg_core.js", "data/Map001.json"))
        assertEquals("", prefixFor())
    }

}
