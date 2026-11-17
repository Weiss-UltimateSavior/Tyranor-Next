package com.tyranor.next.ui.game

import androidx.compose.runtime.saveable.SaverScope
import com.tyranor.next.core.cover.CoverSearchCandidate
import com.tyranor.next.core.cover.decodeCoverSearchCandidate
import com.tyranor.next.core.cover.encodeCoverSearchCandidate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面搜索候选 RememberSaveable 编解码器的 JSON NULL 安全回归。
 */
class CoverSearchCandidateCodecTest {

    @Test
    fun decodeCoverSearchCandidateTreatsJsonNullSafelyAndRejectsMissingSourceOrId() {
        val missingId = JSONObject()
            .put("source", "vndb")
            .put("id", JSONObject.NULL)
            .put("title", "No Id")
            .toString()
        assertNull(decodeCoverSearchCandidate(missingId))

        val missingSource = JSONObject()
            .put("source", "")
            .put("id", "v562")
            .toString()
        assertNull(decodeCoverSearchCandidate(missingSource))

        val withNullOptionals = JSONObject()
            .put("source", "nextmoe")
            .put("id", "207379")
            .put("title", JSONObject.NULL)
            .put("subtitle", "null")
            .put("detail", "Detail")
            .put("score", JSONObject.NULL)
            .put("coverUrl", "https://img.example/cover.webp")
            .put("downloadUrl", JSONObject.NULL)
            .put("vndbId", JSONObject.NULL)
            .put("bangumiId", "null")
            .put("hikarinagiId", JSONObject.NULL)
            .put("nextmoeId", "207379")
            .put("metadataTitle", JSONObject.NULL)
            .toString()

        val decoded = decodeCoverSearchCandidate(withNullOptionals)!!
        assertEquals("nextmoe", decoded.source)
        assertEquals("207379", decoded.id)
        assertEquals("", decoded.title)
        assertEquals("", decoded.subtitle)
        assertEquals("Detail", decoded.detail)
        assertNull(decoded.score)
        assertEquals("https://img.example/cover.webp", decoded.coverUrl)
        assertNull(decoded.downloadUrl)
        assertNull(decoded.vndbId)
        assertNull(decoded.bangumiId)
        assertNull(decoded.hikarinagiId)
        assertEquals("207379", decoded.nextmoeId)
        assertNull(decoded.metadataTitle)
    }

    @Test
    fun encodeCoverSearchCandidateAndSaversRoundTripAllFieldsAndNullSavers() {
        val full = CoverSearchCandidate(
            source = "nextmoe",
            id = "207379",
            title = "サクラノ詩",
            subtitle = "Sakura no Uta",
            detail = "800x1200 · main",
            score = 95,
            coverUrl = "https://img.example/thumb.webp",
            downloadUrl = "https://img.example/full.webp",
            vndbId = "v562",
            bangumiId = "22459",
            hikarinagiId = "hkn-562",
            nextmoeId = "207379",
            metadataTitle = "樱之诗",
        )
        val minimal = CoverSearchCandidate(
            source = "steam",
            id = "12345",
            title = "Minimal",
            subtitle = "",
            detail = "",
            coverUrl = "https://img.example/steam.jpg",
        )

        val decodedFull = decodeCoverSearchCandidate(encodeCoverSearchCandidate(full))
        val decodedMinimal = decodeCoverSearchCandidate(encodeCoverSearchCandidate(minimal))
        assertEquals(full, decodedFull)
        assertEquals(minimal, decodedMinimal)

        val scope = SaverScope { true }
        val savedList = with(CoverSearchCandidatesSaver) { scope.save(listOf(full, minimal)) }!!
        assertEquals(listOf(full, minimal), CoverSearchCandidatesSaver.restore(savedList))

        val savedWork = with(CoverSearchCandidateNullableSaver) { scope.save(full) }!!
        assertEquals(full, CoverSearchCandidateNullableSaver.restore(savedWork))

        val savedNull = with(CoverSearchCandidateNullableSaver) { scope.save(null) }!!
        assertNull(CoverSearchCandidateNullableSaver.restore(savedNull))
    }
}
