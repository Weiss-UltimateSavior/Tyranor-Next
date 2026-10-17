package com.core.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android keycode → 浏览器 keyCode 映射表锚定（MV/MZ/Tyrano 的 keyCode 语义）。 */
class WebKeyCodesTest {

    @Test
    fun lettersMapToUppercaseAscii() {
        assertEquals(65, WebKeyCodes.toJs(KeyEvent.KEYCODE_A))
        assertEquals(81, WebKeyCodes.toJs(KeyEvent.KEYCODE_Q))
        assertEquals(87, WebKeyCodes.toJs(KeyEvent.KEYCODE_W))
        assertEquals(88, WebKeyCodes.toJs(KeyEvent.KEYCODE_X))
        assertEquals(90, WebKeyCodes.toJs(KeyEvent.KEYCODE_Z))
    }

    @Test
    fun digitsAndFunctionKeysMap() {
        assertEquals(48, WebKeyCodes.toJs(KeyEvent.KEYCODE_0))
        assertEquals(57, WebKeyCodes.toJs(KeyEvent.KEYCODE_9))
        assertEquals(112, WebKeyCodes.toJs(KeyEvent.KEYCODE_F1))
        assertEquals(123, WebKeyCodes.toJs(KeyEvent.KEYCODE_F12))
    }

    @Test
    fun controlKeysMap() {
        assertEquals(13, WebKeyCodes.toJs(KeyEvent.KEYCODE_ENTER))
        assertEquals(13, WebKeyCodes.toJs(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(32, WebKeyCodes.toJs(KeyEvent.KEYCODE_SPACE))
        assertEquals(27, WebKeyCodes.toJs(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(9, WebKeyCodes.toJs(KeyEvent.KEYCODE_TAB))
        assertEquals(16, WebKeyCodes.toJs(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(17, WebKeyCodes.toJs(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(18, WebKeyCodes.toJs(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(33, WebKeyCodes.toJs(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(34, WebKeyCodes.toJs(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(8, WebKeyCodes.toJs(KeyEvent.KEYCODE_DEL))
        assertEquals(46, WebKeyCodes.toJs(KeyEvent.KEYCODE_FORWARD_DEL))
    }

    @Test
    fun arrowKeysMap() {
        assertEquals(37, WebKeyCodes.toJs(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(38, WebKeyCodes.toJs(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(39, WebKeyCodes.toJs(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(40, WebKeyCodes.toJs(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test
    fun unsupportedKeysReturnZero() {
        assertEquals(0, WebKeyCodes.toJs(KeyEvent.KEYCODE_CALL))
        assertEquals(0, WebKeyCodes.toJs(KeyEvent.KEYCODE_BACK))
        assertEquals(0, WebKeyCodes.toJs(CanonicalKeys.MOUSE_LEFT))
        assertEquals(false, WebKeyCodes.isSupported(KeyEvent.KEYCODE_BACK))
    }

    @Test
    fun allCatalogFunctionKeysAreWebSupported() {
        InputKeyCatalog.groups()
            .filter { it.titleRes == com.core.engine.R.string.engine_input_group_function }
            .flatMap { it.keys }
            .forEach { entry ->
                org.junit.Assert.assertTrue(
                    "key ${entry.label} must be web supported",
                    WebKeyCodes.isSupported(entry.code),
                )
            }
    }
}
