package com.tyranor.next.ui.game

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 抽屉副文本缓存行为：
 * 1. TTL 5 分钟过期；
 * 2. 仅全部来源回读成功的汇总入缓存——失败文案即时展示、不入缓存；
 * 3. 写后按键首段 uri 精确失效（严格前缀 uri 不连带逐出）；
 * 4. 容量上限逐出最旧条目；
 * 5. locale 入键（BK-41）：同键不同语言为不同变体，语言切换不命中旧语言文案。
 *
 * object 级缓存在用例间共享且容量逐出按 fetchedAt 取全局最旧：用例以 clearForTest 隔离
 * （@Before/@After），人工时间戳（容量逐出用例的 base+index）不再跨用例干扰逐出顺序。
 */
class PlayStatusSummaryCacheTest {

    @Before
    fun setUp() {
        PlayStatusSummaryCache.clearForTest()
    }

    @After
    fun tearDown() {
        PlayStatusSummaryCache.clearForTest()
    }

    private fun uniqueUri(tag: String): String = "file:///cache-test/$tag-${System.nanoTime()}"

    /** 既有用例的缺省 locale 段：键空间扩展（BK-41）后所有变体一律显式携带 locale。 */
    private fun key(
        uri: String,
        bangumiId: String?,
        vndbId: String?,
        hikarinagiId: String?,
        bangumiReady: Boolean,
        vndbReady: Boolean,
        hikarinagiReady: Boolean,
    ): PlayStatusSummaryCache.CacheKey = PlayStatusSummaryCache.key(
        uri, bangumiId, vndbId, hikarinagiId, bangumiReady, vndbReady, hikarinagiReady, "zh-CN",
    )

    @Test
    fun cacheHitsWithinTtlAndExpiresAfterIt() {
        val uri = uniqueUri("ttl")
        val key = key(uri, "1", "2", "3", true, true, true)
        val now = System.currentTimeMillis()

        PlayStatusSummaryCache.put(key, "fresh", now)
        assertEquals("fresh", PlayStatusSummaryCache.get(key))

        // 超过 TTL 的条目惰性过期
        PlayStatusSummaryCache.put(key, "stale", now - 5 * 60_000L - 1)
        assertNull(PlayStatusSummaryCache.get(key))
    }

    @Test
    fun failedSummariesAreNeverCached() {
        val uri = uniqueUri("failure")
        val key = key(uri, "1", null, null, true, false, false)

        // 失败文案行：即时展示但不写入缓存（网络恢复后下次打开立即重读）
        PlayStatusSummaryCache.putIfCacheable(
            key,
            PlayStatusDrawerSummary(line = "VNDB · 状态操作失败", cacheable = false),
        )
        assertNull(PlayStatusSummaryCache.get(key))

        // 全部来源回读成功的汇总正常入缓存
        PlayStatusSummaryCache.putIfCacheable(
            key,
            PlayStatusDrawerSummary(line = "VNDB · 在玩", cacheable = true),
        )
        assertEquals("VNDB · 在玩", PlayStatusSummaryCache.get(key))
    }

    @Test
    fun cacheKeyDistinguishesPerSourceAuthorizationGate() {
        val uri = uniqueUri("gate")
        val authorizedKey = key(uri, "1", "2", null, true, true, false)
        val loggedOutKey = key(uri, "1", "2", null, true, false, false)

        PlayStatusSummaryCache.put(authorizedKey, "VNDB · 在玩", System.currentTimeMillis())
        // 登出 VNDB 后授权门变化即换键：不得命中旧变体
        assertNull(PlayStatusSummaryCache.get(loggedOutKey))
        assertEquals("VNDB · 在玩", PlayStatusSummaryCache.get(authorizedKey))
    }

    @Test
    fun cacheKeyDistinguishesLocaleVariants() {
        // BK-41：缓存值为本地化文案，locale 入键——TTL 内切换语言不得命中旧语言变体；
        // 语言切换后按新键重读一次（与授权态换键同机制）
        val uri = uniqueUri("locale")
        val zhKey = PlayStatusSummaryCache.key(uri, "1", "2", null, true, true, false, "zh-CN")
        val jaKey = PlayStatusSummaryCache.key(uri, "1", "2", null, true, true, false, "ja")
        val enKey = PlayStatusSummaryCache.key(uri, "1", "2", null, true, true, false, "en")

        PlayStatusSummaryCache.put(zhKey, "VNDB · 在玩", System.currentTimeMillis())
        assertNull(PlayStatusSummaryCache.get(jaKey))
        assertNull(PlayStatusSummaryCache.get(enKey))
        PlayStatusSummaryCache.put(jaKey, "VNDB · プレイ中", System.currentTimeMillis())
        assertEquals("VNDB · 在玩", PlayStatusSummaryCache.get(zhKey))
        assertEquals("VNDB · プレイ中", PlayStatusSummaryCache.get(jaKey))
    }

    @Test
    fun invalidateClearsEveryVariantOfTheUri() {
        val uri = uniqueUri("invalidate")
        val otherUri = uniqueUri("invalidate-other")
        val base = System.currentTimeMillis()
        PlayStatusSummaryCache.put(key(uri, "1", null, null, true, false, false), "a", base)
        PlayStatusSummaryCache.put(key(uri, "1", "2", null, true, true, false), "b", base)
        PlayStatusSummaryCache.put(key(otherUri, "1", null, null, true, false, false), "c", base)

        PlayStatusSummaryCache.invalidate(uri)
        assertNull(PlayStatusSummaryCache.get(key(uri, "1", null, null, true, false, false)))
        assertNull(PlayStatusSummaryCache.get(key(uri, "1", "2", null, true, true, false)))
        assertEquals("c", PlayStatusSummaryCache.get(key(otherUri, "1", null, null, true, false, false)))
    }

    @Test
    fun invalidateMatchesTheUriSegmentExactly() {
        // 严格前缀 uri 不得连带逐出
        val uri = uniqueUri("exact")
        val longerUri = "$uri-more"
        val base = System.currentTimeMillis()
        PlayStatusSummaryCache.put(key(uri, "1", null, null, true, false, false), "a", base)
        PlayStatusSummaryCache.put(key(longerUri, "1", null, null, true, false, false), "b", base)

        PlayStatusSummaryCache.invalidate(uri)
        assertNull(PlayStatusSummaryCache.get(key(uri, "1", null, null, true, false, false)))
        assertEquals("b", PlayStatusSummaryCache.get(key(longerUri, "1", null, null, true, false, false)))
    }

    @Test
    fun invalidateHandlesUriContainingPipeSeparator() {
        val pipeUri = "${uniqueUri("pipe")}|part2"
        val prefixUri = pipeUri.substringBefore('|')
        val base = System.currentTimeMillis()
        val pipeKey = key(pipeUri, "1", null, null, true, false, false)
        val prefixKey = key(prefixUri, "1", null, null, true, false, false)
        PlayStatusSummaryCache.put(pipeKey, "pipe-line", base)
        PlayStatusSummaryCache.put(prefixKey, "prefix-line", base)

        PlayStatusSummaryCache.invalidate(pipeUri)
        assertNull(PlayStatusSummaryCache.get(pipeKey))
        assertEquals("prefix-line", PlayStatusSummaryCache.get(prefixKey))
    }

    @Test
    fun capacityCapEvictsOldestEntries() {
        val uri = uniqueUri("capacity")
        val base = System.currentTimeMillis()
        repeat(32) { index ->
            PlayStatusSummaryCache.put(
                key(uri, index.toString(), null, null, true, false, false),
                "line-$index",
                base + index,
            )
        }
        // 写入第 33 条：最旧条目被逐出，其余仍可命中
        PlayStatusSummaryCache.put(
            key(uri, "32", null, null, true, false, false),
            "line-32",
            base + 32,
        )
        assertNull(PlayStatusSummaryCache.get(key(uri, "0", null, null, true, false, false)))
        assertEquals("line-1", PlayStatusSummaryCache.get(key(uri, "1", null, null, true, false, false)))
        assertEquals("line-31", PlayStatusSummaryCache.get(key(uri, "31", null, null, true, false, false)))
        assertEquals("line-32", PlayStatusSummaryCache.get(key(uri, "32", null, null, true, false, false)))
    }

    @Test
    fun invalidateGenerationBlocksStaleInFlightCacheWrite() {
        val uri = uniqueUri("generation")
        val cacheKey = key(uri, "1", null, null, true, false, false)
        val genBefore = PlayStatusSummaryCache.generation(uri)

        PlayStatusSummaryCache.invalidate(uri)

        PlayStatusSummaryCache.putIfCacheable(
            cacheKey,
            PlayStatusDrawerSummary(line = "Bangumi · 在玩", cacheable = true),
            expectedGeneration = genBefore,
        )
        assertNull(PlayStatusSummaryCache.get(cacheKey))

        val genAfter = PlayStatusSummaryCache.generation(uri)
        PlayStatusSummaryCache.putIfCacheable(
            cacheKey,
            PlayStatusDrawerSummary(line = "Bangumi · 玩过", cacheable = true),
            expectedGeneration = genAfter,
        )
        assertEquals("Bangumi · 玩过", PlayStatusSummaryCache.get(cacheKey))
    }

    @Test
    fun cacheKeyDistinguishesCredentialReplacementEpochs() {
        val uri = uniqueUri("epoch")
        val firstAccountKey = PlayStatusSummaryCache.key(
            uri, "1", "2", "3", true, true, true, "zh-CN",
            bangumiAuthEpoch = 1L, vndbAuthEpoch = 1L, hikarinagiAuthEpoch = 1L,
        )
        val replacedVndbAccountKey = PlayStatusSummaryCache.key(
            uri, "1", "2", "3", true, true, true, "zh-CN",
            bangumiAuthEpoch = 1L, vndbAuthEpoch = 2L, hikarinagiAuthEpoch = 1L,
        )

        PlayStatusSummaryCache.put(firstAccountKey, "VNDB · 在玩", System.currentTimeMillis())
        assertNull(PlayStatusSummaryCache.get(replacedVndbAccountKey))
        assertEquals("VNDB · 在玩", PlayStatusSummaryCache.get(firstAccountKey))
    }

    @Test
    fun cacheKeyDistinguishesNextMoeBindingGateAndEpoch() {
        val uri = uniqueUri("nextmoe")
        val k1 = PlayStatusSummaryCache.key(
            uri, null, null, null, false, false, false, "zh-CN",
            nextmoeId = "207379", nextmoeReady = true, nextmoeAuthEpoch = 1L,
        )
        val k2 = PlayStatusSummaryCache.key(
            uri, null, null, null, false, false, false, "zh-CN",
            nextmoeId = "207379", nextmoeReady = true, nextmoeAuthEpoch = 2L,
        )
        val k3 = PlayStatusSummaryCache.key(
            uri, null, null, null, false, false, false, "zh-CN",
            nextmoeId = "207379", nextmoeReady = false, nextmoeAuthEpoch = 1L,
        )
        PlayStatusSummaryCache.put(k1, "未萌NextMoe · 在玩", System.currentTimeMillis())
        assertNull(PlayStatusSummaryCache.get(k2))
        assertNull(PlayStatusSummaryCache.get(k3))
        assertEquals("未萌NextMoe · 在玩", PlayStatusSummaryCache.get(k1))
    }
}
