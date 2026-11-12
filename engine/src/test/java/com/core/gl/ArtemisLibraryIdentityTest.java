package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ArtemisLibraryIdentityTest {
    private static final String ORIGINAL = "e76366a3d04e34520fa9fe30a1b6f0d626ececb5";
    private static final String REVISION_V6 = "2f23ca4efda5953cefd19f0fa09687844a008e42";

    @Test public void identifiesEveryBundledSupportedRevision() {
        String[][] revisions = {
            {"libartemis.so", ORIGINAL},
            {"libartemis-v6.so", "2f23ca4efda5953cefd19f0fa09687844a008e42"},
        };
        for (String[] revision : revisions) {
            assertTrue(ArtemisLibraryIdentity.supports(revision[0]));
            assertTrue(ArtemisLibraryIdentity.matches("/data/user/0/app/files/arm64-v8a/" + revision[0], revision[1]));
        }
    }
    @Test public void rejectsAnotherRevisionUnderAKnownFilename() {
        assertFalse(ArtemisLibraryIdentity.matches("/private/libartemis.so", REVISION_V6));
        assertFalse(ArtemisLibraryIdentity.matches("/private/libartemis-v6.so", ORIGINAL));
    }
    @Test public void rejectsUnsupportedLibrariesEvenWithAKnownBuildId() {
        for (String name : new String[]{"libartemis-compatible.so", "libartemis-clean.so", "unknown.so"}) {
            assertFalse(ArtemisLibraryIdentity.supports(name));
            assertFalse(ArtemisLibraryIdentity.matches(name, ORIGINAL));
        }
    }
    @Test public void rejectsMissingAndMalformedIdentity() {
        assertFalse(ArtemisLibraryIdentity.matches(null, ORIGINAL));
        assertFalse(ArtemisLibraryIdentity.matches("libartemis.so", null));
        assertFalse(ArtemisLibraryIdentity.matches("libartemis.so", ""));
        assertFalse(ArtemisLibraryIdentity.matches("libartemis.so", ORIGINAL.substring(1)));
        assertFalse(ArtemisLibraryIdentity.matches("libartemis.so", "0" + ORIGINAL.substring(1)));
    }
    @Test public void fallbackRecomputesEachCandidateWithoutPromotingAMasterOffRequest() {
        for (String name : new String[]{"artemis", "artemis-v6"}) {
            assertTrue(ArtemisLibraryIdentity.supportsEngine(name, true));
            assertFalse(ArtemisLibraryIdentity.supportsEngine(name, false));
        }
        for (String name : new String[]{null, "", "artemis-compatible", "artemis-clean", "unknown"}) {
            assertFalse(ArtemisLibraryIdentity.supportsEngine(name, true));
        }
    }

}
