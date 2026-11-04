package com.core.siglus;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SiglusEngineAdmissionTest {
    @Test public void runtimeEvidenceRemainsBusyUntilActualProcessOwnerRelease() {
        assertFalse(SiglusEngineAdmission.hasActiveOwner());
        SiglusEngineAdmission.Permit owner = SiglusEngineAdmission.PROCESS.acquire();
        try {
            for (int attempt = 0; attempt < 100; attempt++) {
                assertTrue(SiglusEngineAdmission.hasActiveOwner());
            }
        } finally {
            owner.release();
        }
        assertFalse(SiglusEngineAdmission.hasActiveOwner());
    }
    @Test public void directAndFilteredOwnersCannotOverlap() {
        SiglusEngineAdmission process = new SiglusEngineAdmission();
        SiglusEngineAdmission.Permit filtered = process.acquire();
        assertNotNull(filtered);
        assertNull(process.acquire()); // A newly launched direct host must wait.
        filtered.release();
        SiglusEngineAdmission.Permit direct = process.acquire();
        assertNotNull(direct);
        assertNull(process.acquire()); // The converse has the same process guard.
        direct.release();
    }

    @Test public void anOldCompletionCannotReleaseTheNextOwner() {
        SiglusEngineAdmission process = new SiglusEngineAdmission();
        SiglusEngineAdmission.Permit old = process.acquire();
        old.release();
        SiglusEngineAdmission.Permit current = process.acquire();
        old.release();
        assertNull(process.acquire());
        current.release();
        assertNotNull(process.acquire());
    }

    @Test public void blockedOrFailedDestructionRetainsAdmissionUntilConfirmedCleanup() {
        SiglusEngineAdmission process = new SiglusEngineAdmission();
        SiglusEngineAdmission.Permit retained = process.acquire();
        for (int attempt = 0; attempt < 100; attempt++) assertNull(process.acquire());
        retained.release(); // Only the actual owner can confirm native destruction.
        assertNotNull(process.acquire());
    }
}
