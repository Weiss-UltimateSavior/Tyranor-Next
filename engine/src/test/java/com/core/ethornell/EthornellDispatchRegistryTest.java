package com.core.ethornell;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class EthornellDispatchRegistryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void fastFailureTerminalPersistsWithoutObservingHostProcess() throws Exception {
        File files = temp.newFolder("files");
        String token = EthornellDispatchRegistry.issue(files, "game://😀");
        assertEquals(EthornellDispatchRegistry.Stage.ISSUED, EthornellDispatchRegistry.status(files, token).stage);
        assertTrue(EthornellDispatchRegistry.claim(files, token, "game://😀", 123, "456"));
        assertFalse(EthornellDispatchRegistry.finish(files, token, "game://other", 123, "456"));
        assertFalse(EthornellDispatchRegistry.finish(files, token, "game://😀", 124, "456"));
        assertTrue(EthornellDispatchRegistry.finish(files, token, "game://😀", 123, "456"));
        assertEquals(EthornellDispatchRegistry.Stage.TERMINAL, EthornellDispatchRegistry.records(files).get(0).stage);
        assertFalse(EthornellDispatchRegistry.claim(files, token, "game://😀", 123, "456"));
        EthornellDispatchRegistry.issue(files, "game://next");
        assertNull(EthornellDispatchRegistry.status(files, token)); // safe bounded terminal retirement
        assertFalse(EthornellDispatchRegistry.claim(files, token, "game://😀", 123, "456"));
    }
    @Test public void revokeAndClaimShareAtomicDecisionAndLateRecentsIsRejected() throws Exception {
        File files = temp.newFolder("files");
        String token = EthornellDispatchRegistry.issue(files, "game://a");
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean claimed = new AtomicBoolean(), revoked = new AtomicBoolean();
        Thread claim = new Thread(() -> { try { start.await(); claimed.set(EthornellDispatchRegistry.claim(files, token, "game://a", 12, "34")); } catch (Exception e) { throw new AssertionError(e); } });
        Thread revoke = new Thread(() -> { try { start.await(); revoked.set(EthornellDispatchRegistry.revokeUnclaimed(files, token, "game://a")); } catch (Exception e) { throw new AssertionError(e); } });
        claim.start(); revoke.start(); start.countDown(); claim.join(); revoke.join();
        assertTrue(claimed.get() ^ revoked.get());
        assertFalse(EthornellDispatchRegistry.claim(files, token, "game://a", 12, "34"));
        assertEquals(claimed.get() ? EthornellDispatchRegistry.Stage.CLAIMED : EthornellDispatchRegistry.Stage.REVOKED,
                EthornellDispatchRegistry.status(files, token).stage);
    }
    @Test public void unclaimedAndClaimedRecordsSurviveIssuerDeathAndRecordScan() throws Exception {
        File files = temp.newFolder("files");
        String issued = EthornellDispatchRegistry.issue(files, "game://a");
        String claimed = EthornellDispatchRegistry.issue(files, "game://b");
        assertTrue(EthornellDispatchRegistry.claim(files, claimed, "game://b", 12, "34"));
        assertEquals(2, EthornellDispatchRegistry.records(files).size());
        assertEquals(EthornellDispatchRegistry.Stage.ISSUED, EthornellDispatchRegistry.status(files, issued).stage);
        assertFalse(EthornellDispatchRegistry.revokeUnclaimed(files, claimed, "game://b"));
        assertFalse(EthornellDispatchRegistry.claim(files, issued, "game://other", 12, "34"));
    }
    @Test public void nofollowRootLockAndRecordAndCorruptionFailClosed() throws Exception {
        File files = temp.newFolder("files"); File target = temp.newFolder("target");
        File root = new File(files, "ethornell_dispatch");
        Files.createSymbolicLink(root.toPath(), target.toPath());
        rejects(() -> EthornellDispatchRegistry.records(files)); Files.delete(root.toPath());
        String token = EthornellDispatchRegistry.issue(files, "game://a");
        File record = new File(root, token + ".record"); Files.delete(record.toPath());
        Files.createSymbolicLink(record.toPath(), new File(target, "record").toPath());
        rejects(() -> EthornellDispatchRegistry.status(files, token)); Files.delete(record.toPath());
        Files.write(record.toPath(), new byte[]{1, 2}); rejects(() -> EthornellDispatchRegistry.records(files));
        Files.delete(record.toPath()); Files.delete(new File(root, "metadata.lock").toPath());
        Files.createSymbolicLink(new File(root, "metadata.lock").toPath(), new File(target, "lock").toPath());
        rejects(() -> EthornellDispatchRegistry.records(files));
    }
    @Test public void recordScanAndIssueAreBoundedWithoutDeletingUnconfirmedRecords() throws Exception {
        File files = temp.newFolder("files");
        String token = EthornellDispatchRegistry.issue(files, "game://a");
        File root = new File(files, "ethornell_dispatch");
        // Unissued crash-temp entries are bounded too; issue must not silently purge unresolved data.
        for (int i = 1; i <= 4096; i++) Files.write(new File(root, String.format("%032x.tmp", i)).toPath(), new byte[0]);
        rejects(() -> EthornellDispatchRegistry.records(files));
        rejects(() -> EthornellDispatchRegistry.issue(files, "game://b"));
        assertEquals(EthornellDispatchRegistry.Stage.ISSUED, EthornellDispatchRegistry.status(files, token).stage);
    }
    @Test public void ownerProofDoesNotDependOnAmVisibilityAndRejectsUncertainEvidence() throws Exception {
        File files = temp.newFolder("files");
        String token = EthornellDispatchRegistry.issue(files, "game://a");
        assertTrue(EthornellDispatchRegistry.claim(files, token, "game://a", 12, "34"));
        EthornellDispatchRegistry.Record record = EthornellDispatchRegistry.status(files, token);
        EthornellDispatchRegistry.OwnerProbe alive = (pid, name) -> name.equals("stat") ? stat("34")
                : name.equals("status") ? "Uid:\t10001\t10001\t10001\t10001\n" : "app:ethornell\0";
        assertTrue(EthornellDispatchRegistry.ownerAlive(record, 10001, "app:ethornell", alive));
        assertFalse(EthornellDispatchRegistry.ownerAlive(record, 10001, "app:ethornell", (pid, name) -> {
            throw new NoSuchFileException("owner exited");
        }));
        assertFalse(EthornellDispatchRegistry.ownerAlive(record, 10001, "app:ethornell", (pid, name) -> stat("35")));
        rejects(() -> EthornellDispatchRegistry.ownerAlive(record, 10001, "app:ethornell", (pid, name) -> {
            throw new IOException("permission evidence unavailable");
        }));
        rejects(() -> EthornellDispatchRegistry.ownerAlive(record, 10002, "app:ethornell", alive));
        rejects(() -> EthornellDispatchRegistry.ownerAlive(record, 10001, "app:other", alive));
    }
    @Test public void deadClaimRetiresDurablyButLiveOrUncertainOwnerNeverDoes() throws Exception {
        File files = temp.newFolder("files");
        String token = EthornellDispatchRegistry.issue(files, "game://a");
        assertFalse(EthornellDispatchRegistry.retireDeadClaim(files, token, 10001, "app:ethornell", (pid, name) -> stat("35")));
        assertTrue(EthornellDispatchRegistry.claim(files, token, "game://a", 12, "34"));
        EthornellDispatchRegistry.OwnerProbe alive = (pid, name) -> name.equals("stat") ? stat("34")
                : name.equals("status") ? "Uid:\t10001\t10001\t10001\t10001\n" : "app:ethornell\0";
        assertFalse(EthornellDispatchRegistry.retireDeadClaim(files, token, 10001, "app:ethornell", alive));
        rejects(() -> EthornellDispatchRegistry.retireDeadClaim(files, token, 10001, "app:ethornell", (pid, name) -> {
            throw new IOException("unknown owner evidence");
        }));
        assertEquals(EthornellDispatchRegistry.Stage.CLAIMED, EthornellDispatchRegistry.status(files, token).stage);
        // Simulate owner death during a metadata write before atomic rename.
        Files.write(new File(files, "ethornell_dispatch/" + token + ".tmp").toPath(), new byte[]{1, 2});
        assertTrue(EthornellDispatchRegistry.retireDeadClaim(files, token, 10001, "app:ethornell", (pid, name) -> stat("35")));
        assertEquals(EthornellDispatchRegistry.Stage.TERMINAL, EthornellDispatchRegistry.status(files, token).stage);
        assertTrue(EthornellDispatchRegistry.retireDeadClaim(files, token, 10001, "app:ethornell", alive));
    }
    private static String stat(String start) {
        return "12 (engine worker) S " + "0 ".repeat(18) + start + " 0";
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation) throws Exception {
        try { operation.run(); fail("must fail closed"); } catch (IOException expected) {}
    }
}
