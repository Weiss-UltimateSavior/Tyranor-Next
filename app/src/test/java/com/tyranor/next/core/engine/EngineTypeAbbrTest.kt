package com.tyranor.next.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引擎缩写表（卡片角标）：全部非空、无重复，关键映射与约定一致。
 */
class EngineTypeAbbrTest {

    @Test
    fun allEnginesHaveNonBlankUniqueAbbr() {
        val abbrs = EngineType.entries.map { it.abbr }
        assertTrue(abbrs.all { it.isNotBlank() })
        assertEquals(abbrs.size, abbrs.toSet().size)
    }

    @Test
    fun keyMappingsMatchConvention() {
        assertEquals("ONS", EngineType.ONS.abbr)
        assertEquals("KR2", EngineType.KIRIKIRI.abbr)
        assertEquals("CS2", EngineType.CATSYSTEM2.abbr)
        assertEquals("PSP", EngineType.PSP.abbr)
        assertEquals("AR", EngineType.ARTEMIS.abbr)
        assertEquals("TY", EngineType.TYRANO.abbr)
        assertEquals("MV", EngineType.RPG_MV.abbr)
        assertEquals("MZ", EngineType.RPG_MZ.abbr)
        assertEquals("WEB", EngineType.WEB_OTHER.abbr)
        assertEquals("RPGM", EngineType.RPGMAKER.abbr)
        assertEquals("SIG", EngineType.SIGLUS.abbr)
        assertEquals("RY", EngineType.REALLIVE.abbr)
        assertEquals("AVG", EngineType.AVG32.abbr)
        assertEquals("UK", EngineType.UK2.abbr)
        assertEquals("FVP", EngineType.FVP.abbr)
        assertEquals("NS", EngineType.NINTENDO_SWITCH.abbr)
        assertEquals("VN", EngineType.VN.abbr)
        // 未在约定清单中的引擎：补全映射，避免角标空文案
        assertEquals("RPY", EngineType.RENPY.abbr)
        assertEquals("YR", EngineType.YURIS.abbr)
        assertEquals("PC", EngineType.PC.abbr)
        assertEquals("UNK", EngineType.UNKNOWN.abbr)
    }
}
