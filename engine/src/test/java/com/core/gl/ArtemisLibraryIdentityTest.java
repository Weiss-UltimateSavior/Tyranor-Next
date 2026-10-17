package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ArtemisLibraryIdentityTest {
    private static final String ORIGINAL = "e76366a3d04e34520fa9fe30a1b6f0d626ececb5";
    private static final String REVISION_V4 = "247435d33e6ea1f5812ea7f8518f748e4e3917f4";

    @Test public void identifiesEveryBundledSupportedRevision() {
        String[][] revisions = {
            {"libartemis.so", ORIGINAL},
            {"libartemis-compatible-v2.so", "69e16d9f524ecfcc157f20b5e2baa459a2866332"},
            {"libartemis-v4.so", REVISION_V4},
            {"libartemis-v5.so", "a58d93be16200236703b1518ab07fb132e9fe882"},
            {"libartemis-v6.so", "2f23ca4efda5953cefd19f0fa09687844a008e42"},
        };
        for (String[] revision : revisions) {
            assertTrue(ArtemisLibraryIdentity.supports(revision[0]));
            assertTrue(ArtemisLibraryIdentity.matches("/data/user/0/app/files/arm64-v8a/" + revision[0], revision[1]));
        }
    }
    @Test public void rejectsAnotherRevisionUnderAKnownFilename() {
        assertFalse(ArtemisLibraryIdentity.matches("/private/libartemis.so", REVISION_V4));
        assertFalse(ArtemisLibraryIdentity.matches("/private/libartemis-v4.so", ORIGINAL));
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
        for (String name : new String[]{"artemis", "artemis-compatible-v2", "artemis-v4", "artemis-v5", "artemis-v6"}) {
            assertTrue(ArtemisLibraryIdentity.supportsEngine(name, true));
            assertFalse(ArtemisLibraryIdentity.supportsEngine(name, false));
        }
        for (String name : new String[]{null, "", "artemis-compatible", "artemis-clean", "unknown"}) {
            assertFalse(ArtemisLibraryIdentity.supportsEngine(name, true));
        }
    }

}
