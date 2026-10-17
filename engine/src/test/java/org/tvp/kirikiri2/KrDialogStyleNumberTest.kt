package org.tvp.kirikiri2

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [KrDialogStyle.showNumberBox] 确认路径的解析/钳制契约（纯静态逻辑，不依赖 Android UI）。
 *
 * 语义：空或非法输入回退 initial，随后钳制到规范化后的 [min,max]；解析结果经
 * callback 的 inputText 以字符串传出（取消分支返回空串由 lambda 保证，不在此覆盖）。
 */
class KrDialogStyleNumberTest {

    @Test
    fun parsesAndClampsIntoRange() {
        assertEquals("7", KrDialogStyle.resolveNumberText("7", 5, 0, 10))
        assertEquals("10", KrDialogStyle.resolveNumberText("99", 5, 0, 10))
        assertEquals("0", KrDialogStyle.resolveNumberText("-3", 5, 0, 10))
        assertEquals("3", KrDialogStyle.resolveNumberText(" 3 ", 5, 0, 10))
    }

    @Test
    fun emptyOrInvalidInputFallsBackToInitial() {
        assertEquals("5", KrDialogStyle.resolveNumberText("", 5, 0, 10))
        assertEquals("5", KrDialogStyle.resolveNumberText("   ", 5, 0, 10))
        assertEquals("5", KrDialogStyle.resolveNumberText("abc", 5, 0, 10))
        assertEquals("5", KrDialogStyle.resolveNumberText(null, 5, 0, 10))
        assertEquals("5", KrDialogStyle.resolveNumberText("7x", 5, 0, 10))
    }

    @Test
    fun supportsNegativeNumbersAndUnorderedBounds() {
        assertEquals("-2", KrDialogStyle.resolveNumberText("-2", 5, -10, 10))
        assertEquals("0", KrDialogStyle.resolveNumberText("0", 5, 10, -10))
        assertEquals("-10", KrDialogStyle.resolveNumberText("-99", 5, -10, 10))
    }
}
