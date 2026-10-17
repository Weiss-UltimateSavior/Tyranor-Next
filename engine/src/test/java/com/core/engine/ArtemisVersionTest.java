package com.core.engine;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ArtemisVersionTest {
    @Test public void aliasesAndLoadedLibraryUseOneHostDefinition() {
        for (ArtemisVersion version : ArtemisVersion.values()) {
            assertSame(version, ArtemisVersion.find(" " + version.id + " "));
            assertSame(version, ArtemisVersion.find(version.legacyId));
            assertEquals("lib" + version.engineLibraryName + ".so", version.libraryFilename);
            assertEquals("com.akira.tyranoemu.remote.ArtemisActivityV" + version.id, version.activityClass.getName());
        }
        assertNull(ArtemisVersion.find("auto"));
        assertNull(ArtemisVersion.find("unknown"));
        assertSame(ArtemisVersion.V1, ArtemisVersion.requested("auto"));
        assertSame(ArtemisVersion.V1, ArtemisVersion.requested(""));
        assertNull(ArtemisVersion.requested("unknown"));
    }

    @Test public void legacyFallbackRetriesOriginalOrMovesForwardAndTerminates() {
        assertSame(ArtemisVersion.V1, ArtemisVersion.legacyNext(-1));
        for (ArtemisVersion version : ArtemisVersion.values()) {
            int stage = version.fallbackStage();
            if (version == ArtemisVersion.V6) assertNull(ArtemisVersion.legacyNext(stage));
            else assertSame(ArtemisVersion.values()[stage + 1], ArtemisVersion.legacyNext(stage));
        }
        assertNull(ArtemisVersion.legacyNext(-2));
        assertNull(ArtemisVersion.legacyNext(Integer.MAX_VALUE));
    }
}
