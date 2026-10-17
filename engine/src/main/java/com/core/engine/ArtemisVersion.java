package com.core.engine;

import com.akira.tyranoemu.remote.ArtemisActivityV1;
import com.akira.tyranoemu.remote.ArtemisActivityV2;
import com.akira.tyranoemu.remote.ArtemisActivityV3;
import com.akira.tyranoemu.remote.ArtemisActivityV4;
import com.akira.tyranoemu.remote.ArtemisActivityV5;
import com.akira.tyranoemu.remote.ArtemisActivityV6;
import com.core.nativeplugin.NativePluginConstants;

/** Internal version IDs and bundled hosts. User-visible revision names belong to app resources. */
public enum ArtemisVersion {
    V1("1", "internal.artemis", NativePluginConstants.LIB_ARTEMIS, ArtemisActivityV1.class),
    V2("2", "internal.artemis.compat", NativePluginConstants.LIB_ARTEMIS_COMPATIBLE, ArtemisActivityV2.class),
    V3("3", "internal.artemis.compat.v2", NativePluginConstants.LIB_ARTEMIS_COMPATIBLE_V2, ArtemisActivityV3.class),
    V4("4", "internal.artemis.v4", NativePluginConstants.LIB_ARTEMIS_V4, ArtemisActivityV4.class),
    V5("5", "internal.artemis.v5", NativePluginConstants.LIB_ARTEMIS_V5, ArtemisActivityV5.class),
    V6("6", "internal.artemis.v6", NativePluginConstants.LIB_ARTEMIS_V6, ArtemisActivityV6.class);

    public final String id;
    public final String legacyId;
    public final String libraryFilename;
    public final String engineLibraryName;
    public final Class<?> activityClass;

    ArtemisVersion(String id, String legacyId, String libraryFilename, Class<?> activityClass) {
        this.id = id;
        this.legacyId = legacyId;
        this.libraryFilename = libraryFilename;
        this.engineLibraryName = libraryFilename.substring(3, libraryFilename.length() - 3);
        this.activityClass = activityClass;
    }

    public int fallbackStage() { return ordinal(); }

    /** Historical fallback stages select the following host; -1 retries the original host. */
    public static ArtemisVersion legacyNext(int stage) {
        int index = stage + 1;
        return index >= 0 && index < values().length ? values()[index] : null;
    }

    public static ArtemisVersion find(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        for (ArtemisVersion version : values()) {
            if (version.id.equals(normalized) || version.legacyId.equals(normalized)) return version;
        }
        return null;
    }

    /** Auto initially uses the original host; fingerprint selection and fallback remain in the launcher. */
    public static ArtemisVersion requested(String value) {
        if (value == null || value.trim().isEmpty() || "auto".equals(value.trim())) return V1;
        return find(value);
    }
}
