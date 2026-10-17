package com.core.gl;

import com.core.nativeplugin.NativePluginConstants;
import org.junit.Test;
import static org.junit.Assert.*;

public final class KirikiroidLibraryIdentityTest {
    @Test public void onlyTheThreeBundledHookTargetsAreSupported() {
        assertTrue(KirikiroidLibraryIdentity.supports(NativePluginConstants.LIB_GAME_139));
        assertTrue(KirikiroidLibraryIdentity.supports(NativePluginConstants.LIB_GAME_134));
        assertTrue(KirikiroidLibraryIdentity.supports(NativePluginConstants.LIB_GAME_126));
        for (String library : new String[]{null, "", "libunknown.so"}) {
            assertFalse(KirikiroidLibraryIdentity.supports(library));
        }
    }
}
