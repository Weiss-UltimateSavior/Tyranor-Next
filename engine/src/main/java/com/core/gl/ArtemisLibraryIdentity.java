package com.core.gl;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Version gate for bundled, loader-validated ELF images, not a file-integrity checksum. */
public final class ArtemisLibraryIdentity {
    private static final Map<String, String> BUILD_IDS;
    static {
        Map<String, String> ids = new HashMap<>();
        ids.put("libartemis.so", "e76366a3d04e34520fa9fe30a1b6f0d626ececb5");
        ids.put("libartemis-v6.so", "2f23ca4efda5953cefd19f0fa09687844a008e42");
        BUILD_IDS = Collections.unmodifiableMap(ids);
    }
    private ArtemisLibraryIdentity() {}

    public static boolean supports(String filename) { return BUILD_IDS.containsKey(filename); }

    /** Host fallback names omit the ELF prefix/suffix; null and unsupported candidates remain disabled. */
    public static boolean supportsEngine(String library, boolean requested) {
        return requested && library != null && supports("lib" + library + ".so");
    }

    static boolean matches(String path, String buildId) {
        if (path == null || buildId == null) return false;
        String expected = BUILD_IDS.get(new File(path).getName());
        return expected != null && expected.equals(buildId);
    }
}
