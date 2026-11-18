package com.tyranor.next.ui.runtime

import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeModuleSettingsInputTest {

    @Test
    fun textInputUsesProtocolLengthAndCharacterValidation() {
        val item = RuntimeModuleSettingItemSpec(
            key = "name",
            type = RuntimeModuleSettingItemSpec.Type.TEXT,
            title = "Name",
            defaultValue = "",
            maxLength = 4,
        )

        assertEquals("", parseRuntimeSettingInput(item, ""))
        assertEquals("漢字🙂", parseRuntimeSettingInput(item, "漢字🙂"))
        assertNull(parseRuntimeSettingInput(item, "12345"))
        assertNull(parseRuntimeSettingInput(item, "line\nfeed"))
        assertNull(parseRuntimeSettingInput(item, "tab\tvalue"))
        assertNull(parseRuntimeSettingInput(item, "bad\u007ftext"))
        assertNull(parseRuntimeSettingInput(item, "\ud800"))
    }

    @Test
    fun integerInputMustBeAnIntInsideDeclaredRange() {
        val item = RuntimeModuleSettingItemSpec(
            key = "fps",
            type = RuntimeModuleSettingItemSpec.Type.INTEGER,
            title = "FPS",
            defaultValue = 60,
            min = -10,
            max = 120,
        )

        assertEquals(-10, parseRuntimeSettingInput(item, "-10"))
        assertEquals(120, parseRuntimeSettingInput(item, "120"))
        assertNull(parseRuntimeSettingInput(item, "-11"))
        assertNull(parseRuntimeSettingInput(item, "121"))
        assertNull(parseRuntimeSettingInput(item, "1.5"))
        assertNull(parseRuntimeSettingInput(item, "2147483648"))
    }
}
