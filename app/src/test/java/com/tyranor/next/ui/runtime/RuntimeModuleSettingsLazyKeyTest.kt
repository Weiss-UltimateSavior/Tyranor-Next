package com.tyranor.next.ui.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeModuleSettingsLazyKeyTest {

    @Test
    fun moduleKeysCannotCollideWithHostRows() {
        val moduleKeys = listOf(
            "runtime_settings_state",
            "runtime_settings_save_error",
            "runtime_settings_invalid_overrides",
            "runtime_settings_restore_all_defaults",
            "runtime_file_import:asset",
        ).map(::runtimeModuleSettingLazyKey)
        val hostKeys = listOf(
            runtimeSettingsHostLazyKey("state"),
            runtimeSettingsHostLazyKey("save_error"),
            runtimeSettingsHostLazyKey("invalid_overrides"),
            runtimeSettingsHostLazyKey("restore_all_defaults"),
            runtimeSettingsHostLazyKey("file_import:asset"),
        )

        assertEquals(moduleKeys.size + hostKeys.size, (moduleKeys + hostKeys).toSet().size)
    }
}
