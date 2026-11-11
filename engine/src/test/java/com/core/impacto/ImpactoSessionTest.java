package com.core.impacto;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

import static org.junit.Assert.*;

public class ImpactoSessionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void oneWriterOwnsOsLockAndReleaseAllowsNextLaunch() throws Exception {
        File root = temp.newFolder();
        try (ImpactoSession ignored = ImpactoSession.acquire(root)) {
            assertThrows(IOException.class, () -> ImpactoSession.acquire(root));
        }
        try (ImpactoSession next = ImpactoSession.acquire(root)) { assertNull(next.pending(1000)); }
    }
    public static final class Probe {
        public static void main(String[] args) throws Exception {
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(new File(args[0], "session.lock"), "rw")) {
                java.nio.channels.FileLock lock = file.getChannel().tryLock();
                if (lock == null) System.exit(7);
                lock.release();
            }
        }
    }
    private int probe(File root) throws Exception {
        String classes = new File(Probe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Process process = new ProcessBuilder(new File(System.getProperty("java.home"), "bin/java").getPath(),
                "-cp", classes, Probe.class.getName(), root.getPath()).start();
        assertTrue("File lock probe must terminate", process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
        return process.exitValue();
    }
    @Test public void rejectedLocalAcquireDoesNotDropPosixLockHeldAgainstOtherProcess() throws Exception {
        File root = temp.newFolder();
        try (ImpactoSession active = ImpactoSession.acquire(root)) {
            assertEquals(7, probe(root));
            assertThrows(IOException.class, () -> ImpactoSession.acquire(root));
            assertEquals("A rejected second open must not release the active OS lock", 7, probe(root));
        }
        assertEquals(0, probe(root));
    }
    @Test public void pendingDispatchIsSingleUseAndClaimedSessionCanRestore() throws Exception {
        File root = temp.newFolder(); File session = new File(root, UUID.randomUUID().toString()); assertTrue(session.mkdir());
        try (ImpactoSession admission = ImpactoSession.acquire(root)) {
            admission.dispatch(session.getName(), 1000);
            assertEquals(session.getName(), admission.pending(1001));
            assertThrows(IOException.class, () -> admission.dispatch(UUID.randomUUID().toString(), 1002));
            admission.claim(session, 1003);
            assertNull(admission.pending(1004));
        }
        try (ImpactoSession restored = ImpactoSession.acquire(root)) { restored.claim(session, 2000); }
    }
    @Test public void abandonedOrClockReversedDispatchExpires() throws Exception {
        File root = temp.newFolder(); String token = UUID.randomUUID().toString();
        try (ImpactoSession admission = ImpactoSession.acquire(root)) {
            admission.dispatch(token, 1000);
            assertNull(admission.pending(1001 + ImpactoSession.DISPATCH_TTL_MS));
            admission.dispatch(token, 5000);
            assertNull(admission.pending(0));
        }
    }
    @Test public void wrongTokenCannotClaimOrClearAnotherLaunch() throws Exception {
        File root = temp.newFolder(); String token = UUID.randomUUID().toString();
        File wrong = new File(root, UUID.randomUUID().toString()); assertTrue(wrong.mkdir());
        try (ImpactoSession admission = ImpactoSession.acquire(root)) {
            admission.dispatch(token, 1000); admission.clear(wrong.getName());
            assertEquals(token, admission.pending(1001));
            assertThrows(IOException.class, () -> admission.claim(wrong, 1002));
            admission.clear(token); assertNull(admission.pending(1003));
        }
    }
    @Test public void newerPendingLaunchPreventsOldRecentsSessionClaim() throws Exception {
        File root = temp.newFolder(); File old = new File(root, UUID.randomUUID().toString()); assertTrue(old.mkdir());
        try (ImpactoSession admission = ImpactoSession.acquire(root)) {
            admission.dispatch(old.getName(), 1000); admission.claim(old, 1001);
            admission.dispatch(UUID.randomUUID().toString(), 2000);
            assertThrows(IOException.class, () -> admission.claim(old, 2001));
        }
    }
}
