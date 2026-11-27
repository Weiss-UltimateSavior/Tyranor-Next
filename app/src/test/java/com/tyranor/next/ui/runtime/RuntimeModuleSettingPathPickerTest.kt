package com.tyranor.next.ui.runtime

import com.tyranor.next.core.engine.runtime.RuntimeModuleSettingItemSpec.Type
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeModuleSettingPathPickerTest {
    @Test
    fun pathResultRequiresOriginalDescriptorAndFieldType() {
        assertTrue(
            isRuntimeModuleSettingPathRequestCompatible(
                requestFingerprint = "descriptor-v1",
                requestType = Type.FILE,
                currentFingerprint = "descriptor-v1",
                currentType = Type.FILE,
            ),
        )
        assertFalse(
            isRuntimeModuleSettingPathRequestCompatible(
                requestFingerprint = "descriptor-v1",
                requestType = Type.FILE,
                currentFingerprint = "descriptor-v2",
                currentType = Type.FILE,
            ),
        )
        assertFalse(
            isRuntimeModuleSettingPathRequestCompatible(
                requestFingerprint = "descriptor-v1",
                requestType = Type.FILE,
                currentFingerprint = "descriptor-v1",
                currentType = Type.FOLDER,
            ),
        )
        assertFalse(
            isRuntimeModuleSettingPathRequestCompatible(
                requestFingerprint = null,
                requestType = Type.FILE,
                currentFingerprint = "descriptor-v1",
                currentType = Type.FILE,
            ),
        )
    }
}
