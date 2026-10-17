package com.core.gl;

import com.core.nativeplugin.NativePluginConstants;

/** Shared library-name gate. Native installation validates the loaded image's Build ID and hook ABI. */
public final class KirikiroidLibraryIdentity {
    private KirikiroidLibraryIdentity() {}
    public static boolean supports(String filename) {
        return NativePluginConstants.LIB_GAME_139.equals(filename)
                || NativePluginConstants.LIB_GAME_134.equals(filename)
                || NativePluginConstants.LIB_GAME_126.equals(filename);
    }
}
