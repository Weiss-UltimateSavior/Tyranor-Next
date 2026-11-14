package com.tyranor.next.core.status

import com.tyranor.next.core.auth.BangumiAuthError
import com.tyranor.next.core.auth.VndbPermissionTier
import com.tyranor.next.core.auth.evaluateVndbPermissionTier
import com.tyranor.next.core.engine.EngineType
import com.tyranor.next.core.game.model.ScanGame
import com.tyranor.next.core.settings.AppSettingsStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 游玩状态映射纯函数：Bangumi 收藏类型合法性与 VNDB 四态互斥 unset 集合。 */
class PlayStatusMappingTest {

    @Test
    fun bangumiCollectTypesAcceptsExactlyTheFiveValidTypes() {
        BangumiCollectTypes.ALL.forEach { assertTrue(isBangumiCollectType(it)) }
        assertFalse(isBangumiCollectType(0))
        assertFalse(isBangumiCollectType(6))
        assertFalse(isBangumiCollectType(-1))
    }

    @Test
    fun vndbLabelsToUnsetReturnsTheOtherThreeExclusiveLabels() {
        VndbLabels.ALL.forEach { label ->
            assertEquals(VndbLabels.ALL.filterNot { it == label }, vndbLabelsToUnset(label))
        }
    }

    @Test
    fun vndbLabelsToUnsetNeverContainsTheTargetLabel() {
        VndbLabels.ALL.forEach { label ->
            assertFalse(label in vndbLabelsToUnset(label))
        }
    }

    @Test
    fun evaluateVndbPermissionTierDistinguishesWriteReadAndPublicTiers() {
        // Tier 1: listwrite 具备写入能力（无论是否包含 listread）
        assertEquals(VndbPermissionTier.WRITE, evaluateVndbPermissionTier(listOf("listwrite")))
        assertEquals(VndbPermissionTier.WRITE, evaluateVndbPermissionTier(listOf("listread", "listwrite")))
        assertEquals(VndbPermissionTier.WRITE, evaluateVndbPermissionTier(listOf("listwrite", "other")))

        // Tier 2: 仅 listread 为只读（可访问私密条目）
        assertEquals(VndbPermissionTier.READ_PRIVATE, evaluateVndbPermissionTier(listOf("listread")))
        assertEquals(VndbPermissionTier.READ_PRIVATE, evaluateVndbPermissionTier(listOf("listread", "other")))

        // Tier 3: 无附加权限 / 未勾选为只读公开（未勾选任何权限亦合法）
        assertEquals(VndbPermissionTier.READ_PUBLIC, evaluateVndbPermissionTier(emptyList()))
        assertEquals(VndbPermissionTier.READ_PUBLIC, evaluateVndbPermissionTier(listOf("other")))
    }

    @Test
    fun hikarinagiStatusesAcceptsExactlyTheFiveValidStatuses() {
        HikarinagiStatuses.ALL.forEach { assertTrue(isHikarinagiStatus(it)) }
        assertFalse(isHikarinagiStatus(null))
        assertFalse(isHikarinagiStatus(""))
        assertFalse(isHikarinagiStatus("PLANNING"))
        assertFalse(isHikarinagiStatus("UNKNOWN"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeBangumiStatusRejectsInvalidCollectType() {
        assertFalse(isBangumiCollectType(99))
        PlayStatusService.writeBangumiStatus(
            object : android.content.ContextWrapper(null) {},
            "123",
            99,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeBangumiStatusRejectsBlankSubjectId() {
        // 空绑定 id 前置防御：UI 快照漂移兜底，不得向远端发空 id 请求
        PlayStatusService.writeBangumiStatus(
            object : android.content.ContextWrapper(null) {},
            "  ",
            BangumiCollectTypes.DONE,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeVndbStatusRejectsBlankVnId() {
        PlayStatusService.writeVndbStatus(
            object : android.content.ContextWrapper(null) {},
            "  ",
            VndbLabels.PLAYING,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeVndbStatusRejectsInvalidLabel() {
        PlayStatusService.writeVndbStatus(
            object : android.content.ContextWrapper(null) {},
            "v123",
            999,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeHikarinagiStatusRejectsInvalidStatus() {
        PlayStatusService.writeHikarinagiStatus(
            object : android.content.ContextWrapper(null) {},
            "gal-123",
            "INVALID",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeHikarinagiStatusRejectsBlankId() {
        PlayStatusService.writeHikarinagiStatus(
            object : android.content.ContextWrapper(null) {},
            "   ",
            HikarinagiStatuses.GOING,
        )
    }

    @Test
    fun toPlayStatusErrorPreservesExplicitErrors() {
        val ex = PlayStatusException(PlayStatusError.VNDB_TOKEN_PERMISSION)
        assertEquals(PlayStatusError.VNDB_TOKEN_PERMISSION, ex.toPlayStatusError("TestTag"))
        val hknEx = PlayStatusException(PlayStatusError.HIKARINAGI_TOKEN_PERMISSION)
        assertEquals(PlayStatusError.HIKARINAGI_TOKEN_PERMISSION, hknEx.toPlayStatusError("TestTag"))
    }

    @Test
    fun bangumiStatusToErrorDistinguishesExpiredPermissionAndNetwork() {
        // 与同文件 VNDB / Hikarinagi 的既有分档对齐：401=失效、403=权限不足专列
        assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, bangumiStatusToError(401))
        assertEquals(PlayStatusError.BANGUMI_TOKEN_PERMISSION, bangumiStatusToError(403))

        // 其余非 2xx（含传输失败 status=0）保守回退网络档，不冒充权限或失效
        assertEquals(PlayStatusError.BANGUMI_NETWORK, bangumiStatusToError(400))
        assertEquals(PlayStatusError.BANGUMI_NETWORK, bangumiStatusToError(404))
        assertEquals(PlayStatusError.BANGUMI_NETWORK, bangumiStatusToError(429))
        assertEquals(PlayStatusError.BANGUMI_NETWORK, bangumiStatusToError(500))
        assertEquals(PlayStatusError.BANGUMI_NETWORK, bangumiStatusToError(0))
    }

    @Test
    fun bangumiTokenRefreshFailureGradesByNeedsReauth() {
        // needsReauth 已置位（刷新被 400/401 明确拒绝）→ 令牌失效档
        assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, bangumiTokenRefreshFailureToError(true))
        // 传输失败等未确认失效 → 网络档，不诱导用户重新授权
        assertEquals(PlayStatusError.BANGUMI_NETWORK, bangumiTokenRefreshFailureToError(false))
    }

    @Test
    fun hikarinagiTokenRefreshFailureGradesByNeedsReauth() {
        assertEquals(PlayStatusError.HIKARINAGI_TOKEN_EXPIRED, hikarinagiTokenRefreshFailureToError(true))
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, hikarinagiTokenRefreshFailureToError(false))
    }

    @Test
    fun bangumiTokenRefreshFailureGradesConfigMissingAsReauth() {
        // 刷新配置缺失（OAuth 应用凭据未配置）：刷新不可能成功，按「需重新授权」档提示，
        // 不再误报网络档（刷新配置缺失按重授权档处置）
        assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, bangumiTokenRefreshFailureToError(false, true))
        // 与既有分档的组合行为保持不变
        assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, bangumiTokenRefreshFailureToError(true, false))
        assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, bangumiTokenRefreshFailureToError(true, true))
        assertEquals(PlayStatusError.BANGUMI_NETWORK, bangumiTokenRefreshFailureToError(false, false))
    }

    @Test
    fun hikarinagiTokenRefreshFailureGradesConfigMissingAsReauth() {
        // 刷新配置缺失（OAuth 应用凭据未配置）：与 Bangumi 侧同款，按「需重新授权」档提示，
        // 不再误报网络档
        assertEquals(PlayStatusError.HIKARINAGI_TOKEN_EXPIRED, hikarinagiTokenRefreshFailureToError(false, true))
        // 与既有分档的组合行为保持不变
        assertEquals(PlayStatusError.HIKARINAGI_TOKEN_EXPIRED, hikarinagiTokenRefreshFailureToError(true, false))
        assertEquals(PlayStatusError.HIKARINAGI_TOKEN_EXPIRED, hikarinagiTokenRefreshFailureToError(true, true))
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, hikarinagiTokenRefreshFailureToError(false, false))
    }

    @Test
    fun vndbAuthInfoStatusGradesPermissionSeparatelyFromInvalid() {
        // 校验路径 401/403 与读/写路径分档统一：401 = 失效、403 = 权限档（/authinfo 403
        // 的确切语义未经实机证实，保守对齐读/写路径，不与 401 失效混同）
        assertEquals(PlayStatusError.VNDB_TOKEN_INVALID, vndbAuthInfoStatusToError(401))
        assertEquals(PlayStatusError.VNDB_TOKEN_PERMISSION, vndbAuthInfoStatusToError(403))

        // 其余非 2xx（含传输失败 status=0）保守回退网络档
        assertEquals(PlayStatusError.VNDB_NETWORK, vndbAuthInfoStatusToError(400))
        assertEquals(PlayStatusError.VNDB_NETWORK, vndbAuthInfoStatusToError(429))
        assertEquals(PlayStatusError.VNDB_NETWORK, vndbAuthInfoStatusToError(500))
        assertEquals(PlayStatusError.VNDB_NETWORK, vndbAuthInfoStatusToError(0))
    }

    @Test
    fun parseVndbUlistStatusThrowsNetworkOnNonJsonBody() {
        // 2xx 非 JSON 响应体按网络档抛出，不得误报「未在列表」
        try {
            parseVndbUlistStatus(json = null, vnId = "v17")
            throw AssertionError("expected PlayStatusException")
        } catch (expected: PlayStatusException) {
            assertEquals(PlayStatusError.VNDB_NETWORK, expected.error)
        }
    }

    @Test
    fun parseVndbUlistStatusReadsLabelsOfMatchedEntry() {
        val json = JSONObject()
            .put(
                "results",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "v17")
                            .put(
                                "labels",
                                org.json.JSONArray()
                                    .put(JSONObject().put("id", VndbLabels.PLAYING))
                                    .put(JSONObject().put("id", 99)),
                            ),
                    ),
            )
        val status = parseVndbUlistStatus(json, "v17")
        assertEquals(setOf(VndbLabels.PLAYING, 99), status.labelIds)
    }

    @Test
    fun parseVndbUlistStatusTreatsMissingEntryOrLabelsAsNotListed() {
        // 正常 2xx JSON 包络下无命中行 / 无 labels 数组 = 「未在列表」的合法空态
        val json = JSONObject().put("results", org.json.JSONArray().put(JSONObject().put("id", "v18")))
        assertEquals(emptySet<Int>(), parseVndbUlistStatus(json, "v17").labelIds)
        assertEquals(
            emptySet<Int>(),
            parseVndbUlistStatus(JSONObject().put("results", org.json.JSONArray()), "v17").labelIds,
        )
        assertEquals(
            emptySet<Int>(),
            parseVndbUlistStatus(
                JSONObject().put(
                    "results",
                    org.json.JSONArray().put(JSONObject().put("id", "v17")),
                ),
                "v17",
            ).labelIds,
        )
    }

    @Test
    fun bangumiWriteNeedsReauthReasonGradesOnly401() {
        // BK-05A：写路径仅 401（服务端明确拒绝 = 失效/被吊销）落 needsReauth(EXPIRED)，
        // 与读路径 401 分档对齐；403 维持只抛错不落档（无独立权限位），其余不触碰授权态
        assertEquals(BangumiAuthError.EXPIRED.name, bangumiWriteNeedsReauthReason(401))
        assertNull(bangumiWriteNeedsReauthReason(403))
        assertNull(bangumiWriteNeedsReauthReason(400))
        assertNull(bangumiWriteNeedsReauthReason(404))
        assertNull(bangumiWriteNeedsReauthReason(429))
        assertNull(bangumiWriteNeedsReauthReason(500))
        assertNull(bangumiWriteNeedsReauthReason(0))
    }

    @Test
    fun bangumiWriteReauthReasonAlignsWithReadPathGradingAndUiBranch() {
        // 决策链自洽：401 → EXPIRED 落档后，(a) bangumiStatusToError 同报失效档；
        // (b) EXPIRED.name 与设置页摘要的专项重登文案分支、(c) 刷新短路后的
        // bangumiTokenRefreshFailureToError(needsReauth=true) 分档（后续写自愈路径的报错形态）
        // 三处消费同一错误码，不得各写各的字面量
        val reason = bangumiWriteNeedsReauthReason(401)!!
        assertEquals(BangumiAuthError.EXPIRED.name, reason)
        assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, bangumiStatusToError(401))
        assertEquals(PlayStatusError.BANGUMI_TOKEN_EXPIRED, bangumiTokenRefreshFailureToError(needsReauth = true))
        // 落档值非空即置位 needsReauth（getStatus: needsReauth = lastError.isNotBlank()），
        // 空串永远不会成为落档值，避免「落档即未失效」的自相矛盾
        assertTrue(reason.isNotBlank())
    }

    @Test
    fun unbindTransformKeepsSharedMetadataTitleForEverySource() {
        // BK-42B：解绑一律保留 metadataTitle（来源未持久化，误清刮削标题的代价高于残留代价），
        // 解绑最后来源不再无条件清档；绑定 id 只清对应来源、其余来源不动
        val game = ScanGame(
            title = "标题",
            uri = "/games/transform",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            vndbId = "v17",
            bangumiId = "4154",
            hikarinagiId = "hkn1",
            metadataTitle = "刮削或绑定写入的共享标题",
        )

        val unbindVndb = unbindSubjectTransform(AppSettingsStore.COVER_SOURCE_VNDB, game)
        assertEquals(null, unbindVndb?.vndbId)
        assertEquals("4154", unbindVndb?.bangumiId)
        assertEquals("hkn1", unbindVndb?.hikarinagiId)
        assertEquals("刮削或绑定写入的共享标题", unbindVndb?.metadataTitle)

        val unbindBangumi = unbindSubjectTransform(AppSettingsStore.COVER_SOURCE_BANGUMI, game)
        assertEquals(null, unbindBangumi?.bangumiId)
        assertEquals("v17", unbindBangumi?.vndbId)
        assertEquals("hkn1", unbindBangumi?.hikarinagiId)
        assertEquals("刮削或绑定写入的共享标题", unbindBangumi?.metadataTitle)

        // 解绑最后来源（其余两源已空）：metadataTitle 仍保留（与旧行为的唯一行为差异）
        val lastOne = game.copy(vndbId = null, bangumiId = null)
        val unbindLast = unbindSubjectTransform(AppSettingsStore.COVER_SOURCE_HIKARINAGI, lastOne)
        assertEquals(null, unbindLast?.hikarinagiId)
        assertEquals("刮削或绑定写入的共享标题", unbindLast?.metadataTitle)
    }

    @Test
    fun unbindTransformIsIdentityWhenAlreadyUnbound() {
        // 本已解绑：transform 结果等于 current（facade 六列比较拦截落库，按幂等成功重读复放）
        val game = ScanGame(
            title = "标题",
            uri = "/games/idempotent",
            engine = EngineType.ONS,
            launchTarget = "data.exe",
            metadataTitle = "保留标题",
        )
        assertEquals(game, unbindSubjectTransform(AppSettingsStore.COVER_SOURCE_VNDB, game))
        assertEquals(game, unbindSubjectTransform(AppSettingsStore.COVER_SOURCE_BANGUMI, game))
        assertEquals(game, unbindSubjectTransform(AppSettingsStore.COVER_SOURCE_HIKARINAGI, game))
    }

    @Test
    fun unbindTransformRejectsUnknownSource() {
        val game = ScanGame(
            title = "标题",
            uri = "/games/unknown",
            engine = EngineType.ONS,
            launchTarget = "data.exe",
        )
        assertNull(unbindSubjectTransform("steam", game))
        assertNull(unbindSubjectTransform("", game))
    }

    @Test
    fun bindTransformRefreshesMetadataTitleOnSameIdRebind() {
        // BK-44：删除同 id 早退后，重绑同条目携带有效新标题时刷新 metadataTitle
        val game = ScanGame(
            title = "文件名标题",
            uri = "/games/rebind",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            vndbId = "v17",
            metadataTitle = "旧标题",
        )
        val rebound = bindSubjectTransform(AppSettingsStore.COVER_SOURCE_VNDB, "v17", "候选新标题", game)
        assertEquals("v17", rebound?.vndbId)
        assertEquals("候选新标题", rebound?.metadataTitle)
        // 绑定 id 未变但标题刷新：与 current 不等 → facade 正常落库（旧早退路径会跳过刷新）
        assertEquals(game.copy(metadataTitle = "候选新标题"), rebound)
    }

    @Test
    fun bindTransformIsIdentityWhenNothingEffectivelyChanges() {
        // 同 id 且无有效标题变化（未携带 / 空白标题）：返回值与 current 相等 → facade 拦截落库，
        // 经 bindCoverTransform 重读复放按幂等成功返回，不报失败（BK-45 语义）
        val game = ScanGame(
            title = "文件名标题",
            uri = "/games/nochange",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            bangumiId = "4154",
            metadataTitle = "旧标题",
        )
        assertEquals(game, bindSubjectTransform(AppSettingsStore.COVER_SOURCE_BANGUMI, "4154", null, game))
        assertEquals(game, bindSubjectTransform(AppSettingsStore.COVER_SOURCE_BANGUMI, " 4154 ", "   ", game))
        assertEquals(game, bindSubjectTransform(AppSettingsStore.COVER_SOURCE_BANGUMI, "4154", "", game))
    }

    @Test
    fun bindTransformBindsNewIdAndTrimsTitle() {
        // 新绑定：写入 trim 后的 id 与 trim 后的标题；标题空白时保留现有 metadataTitle（BK-42B
        // 保守保留的自洽面：绑定路径同样不会把标题清空）
        val game = ScanGame(
            title = "文件名标题",
            uri = "/games/bind",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
            metadataTitle = "既有标题",
        )
        val bound = bindSubjectTransform(AppSettingsStore.COVER_SOURCE_HIKARINAGI, " hkn-77 ", " 新标题 ", game)
        assertEquals("hkn-77", bound?.hikarinagiId)
        assertEquals("新标题", bound?.metadataTitle)

        val boundBlankTitle = bindSubjectTransform(AppSettingsStore.COVER_SOURCE_HIKARINAGI, "hkn-88", "   ", game)
        assertEquals("hkn-88", boundBlankTitle?.hikarinagiId)
        assertEquals("既有标题", boundBlankTitle?.metadataTitle)
    }

    @Test
    fun bindTransformRejectsBlankIdAndUnknownSource() {
        val game = ScanGame(
            title = "标题",
            uri = "/games/blank",
            engine = EngineType.ONS,
            launchTarget = "data.exe",
        )
        assertNull(bindSubjectTransform(AppSettingsStore.COVER_SOURCE_VNDB, "   ", "标题", game))
        assertNull(bindSubjectTransform("steam", "v17", "标题", game))
        assertNull(bindSubjectTransform("", "", null, game))
    }

    @Test
    fun parseHikarinagiRateStatusHandlesUnratedNullDataAndErrors() {
        val unratedNull = JSONObject().put("success", true).put("data", JSONObject.NULL)
        assertEquals(
            PlayStatusService.HikarinagiStatus(exists = false, status = null),
            parseHikarinagiRateStatus(unratedNull),
        )

        val unratedMissing = JSONObject().put("success", true)
        assertEquals(
            PlayStatusService.HikarinagiStatus(exists = false, status = null),
            parseHikarinagiRateStatus(unratedMissing),
        )

        val rated = JSONObject()
            .put("success", true)
            .put("data", JSONObject().put("status", HikarinagiStatuses.COMPLETED))
        assertEquals(
            PlayStatusService.HikarinagiStatus(exists = true, status = HikarinagiStatuses.COMPLETED),
            parseHikarinagiRateStatus(rated),
        )

        val nullStatus = JSONObject()
            .put("success", true)
            .put("data", JSONObject().put("status", JSONObject.NULL))
        assertEquals(
            PlayStatusService.HikarinagiStatus(exists = true, status = null),
            parseHikarinagiRateStatus(nullStatus),
        )

        val errNull = assertThrows(PlayStatusException::class.java) {
            parseHikarinagiRateStatus(null)
        }
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, errNull.error)

        val errFalse = assertThrows(PlayStatusException::class.java) {
            parseHikarinagiRateStatus(JSONObject().put("success", false))
        }
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, errFalse.error)

        val errInvalidData = assertThrows(PlayStatusException::class.java) {
            parseHikarinagiRateStatus(JSONObject().put("success", true).put("data", "unexpected"))
        }
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, errInvalidData.error)
    }

    @Test
    fun hikarinagiNonSuccessToErrorSeparates404FromNetwork() {
        assertEquals(PlayStatusError.HIKARINAGI_SUBJECT_NOT_FOUND, hikarinagiNonSuccessToError(404))
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, hikarinagiNonSuccessToError(400))
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, hikarinagiNonSuccessToError(429))
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, hikarinagiNonSuccessToError(500))
        assertEquals(PlayStatusError.HIKARINAGI_NETWORK, hikarinagiNonSuccessToError(0))
    }

    @Test
    fun nextmoeWorkStatesAndBodyPreserveCompletionExceptWish() {
        NextMoeWorkStates.ALL.forEach { assertTrue(isNextMoeWorkState(it)) }
        assertFalse(isNextMoeWorkState(null))
        assertFalse(isNextMoeWorkState(""))
        assertFalse(isNextMoeWorkState("completed"))

        val doneBody = JSONObject(buildNextMoePutWorkStateBody(NextMoeWorkStates.DONE, "all"))
        assertEquals("done", doneBody.getString("state"))
        assertEquals("all", doneBody.getString("completion"))

        // wish 不允许携带 completion
        val wishBody = JSONObject(buildNextMoePutWorkStateBody(NextMoeWorkStates.WISH, "all"))
        assertEquals("wish", wishBody.getString("state"))
        assertFalse(wishBody.has("completion"))

        // 非合法 completion 不写入
        val unknownCompletionBody = JSONObject(buildNextMoePutWorkStateBody(NextMoeWorkStates.DOING, "other"))
        assertEquals("doing", unknownCompletionBody.getString("state"))
        assertFalse(unknownCompletionBody.has("completion"))
    }

    @Test
    fun parseNextMoeWorkStateAndErrorsHandleEdgeCases() {
        val stateJson = JSONObject()
            .put("object", "work_state")
            .put("work_id", "207379")
            .put("state", "done")
            .put("completion", "main")
        assertEquals(
            PlayStatusService.NextMoeStatus(exists = true, state = "done", completion = "main"),
            parseNextMoeWorkState(stateJson),
        )

        val errNull = assertThrows(PlayStatusException::class.java) {
            parseNextMoeWorkState(null)
        }
        assertEquals(PlayStatusError.NEXTMOE_NETWORK, errNull.error)

        assertEquals(PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND, nextMoeNonSuccessToError(404, "ENTITY_MERGED"))
        assertEquals(PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND, nextMoeNonSuccessToError(410, "ENTITY_DELETED"))
        assertEquals(PlayStatusError.NEXTMOE_RATE_LIMITED, nextMoeNonSuccessToError(429, "RATE_LIMITED"))
        assertEquals(PlayStatusError.NEXTMOE_RATE_LIMITED, nextMoeNonSuccessToError(429, "QUOTA_EXCEEDED"))
        assertEquals(PlayStatusError.NEXTMOE_NETWORK, nextMoeNonSuccessToError(503, "SERVICE_UNAVAILABLE"))
        assertEquals(PlayStatusError.NEXTMOE_NETWORK, nextMoeNonSuccessToError(0, null))

        assertEquals(PlayStatusError.NEXTMOE_TOKEN_EXPIRED, nextMoeTokenRefreshFailureToError(needsReauth = true))
        assertEquals(
            PlayStatusError.NEXTMOE_TOKEN_EXPIRED,
            nextMoeTokenRefreshFailureToError(needsReauth = false, configMissing = true),
        )
        assertEquals(PlayStatusError.NEXTMOE_NETWORK, nextMoeTokenRefreshFailureToError(needsReauth = false))
    }

    @Test
    fun bindAndUnbindNextMoeTransformRoundTrip() {
        val game = ScanGame(
            title = "樱之诗",
            uri = "/games/sakura",
            engine = EngineType.KIRIKIRI,
            launchTarget = "data.xp3",
        )
        val bound = bindSubjectTransform(AppSettingsStore.COVER_SOURCE_NEXTMOE, "207379", "樱之诗", game)
        assertEquals("207379", bound?.nextmoeId)
        assertEquals("樱之诗", bound?.metadataTitle)

        val unbound = unbindSubjectTransform(AppSettingsStore.COVER_SOURCE_NEXTMOE, bound!!)
        assertNull(unbound?.nextmoeId)
        assertEquals("樱之诗", unbound?.metadataTitle)
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeNextMoeStatusRejectsInvalidState() {
        PlayStatusService.writeNextMoeStatus(
            object : android.content.ContextWrapper(null) {},
            "207379",
            "INVALID",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun writeNextMoeStatusRejectsBlankId() {
        PlayStatusService.writeNextMoeStatus(
            object : android.content.ContextWrapper(null) {},
            "   ",
            NextMoeWorkStates.DOING,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun readVndbStatusRejectsBlankVnId() {
        PlayStatusService.readVndbStatus(
            object : android.content.ContextWrapper(null) {},
            "   ",
        )
    }

    @Test
    fun nextmoeNotFoundDistinguishesUnmarkedFromMergedDeletedAndNonJson() {
        // 404 JSON NOT_FOUND -> 未记录状态
        assertTrue(isNextMoeNotFoundUnmarked(404, JSONObject().put("code", "NOT_FOUND"), "NOT_FOUND"))
        assertTrue(isNextMoeNotFoundUnmarked(404, JSONObject(), null))
        // 404 ENTITY_MERGED / ENTITY_DELETED 或非 JSON 404 -> 不得误判为未记录
        assertFalse(isNextMoeNotFoundUnmarked(404, JSONObject().put("code", "ENTITY_MERGED"), "ENTITY_MERGED"))
        assertFalse(isNextMoeNotFoundUnmarked(404, JSONObject().put("code", "ENTITY_DELETED"), "ENTITY_DELETED"))
        assertFalse(isNextMoeNotFoundUnmarked(404, null, null))
        assertEquals(PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND, nextMoeNonSuccessToError(404, "ENTITY_MERGED"))
        assertEquals(PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND, nextMoeNonSuccessToError(404, "ENTITY_DELETED"))
        assertEquals(PlayStatusError.NEXTMOE_SUBJECT_NOT_FOUND, nextMoeNonSuccessToError(404, "NOT_FOUND"))
    }
}
