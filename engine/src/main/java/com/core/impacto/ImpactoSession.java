package com.core.impacto;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** OS-owned admission lock. A pending dispatch expires, but an active native writer never times out. */
final class ImpactoSession implements Closeable {
    // POSIX closes release a process's locks on that inode, even through another descriptor.
    // Refuse duplicate local owners BEFORE opening a second RandomAccessFile.
    private static final java.util.Set<File> OWNED = new java.util.HashSet<>();
    private boolean closed;
    static final long DISPATCH_TTL_MS = 60_000;
    private final File root;
    private final RandomAccessFile handle;
    private final FileLock lock;
    private ImpactoSession(File root, RandomAccessFile handle, FileLock lock) {
        this.root = root; this.handle = handle; this.lock = lock;
    }
    static synchronized ImpactoSession acquire(File root) throws IOException {
        File canonicalRoot = root.getCanonicalFile();
        if (!OWNED.add(canonicalRoot)) throw new IOException("Impacto session is active in this process");
        RandomAccessFile handle = null;
        try {
            File lockFile = ImpactoFiles.child(canonicalRoot, "session.lock");
            handle = new RandomAccessFile(lockFile, "rw");
            FileLock lock = handle.getChannel().tryLock();
            if (lock == null) throw new IOException("Impacto session is active");
            return new ImpactoSession(canonicalRoot, handle, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            OWNED.remove(canonicalRoot);
            if (handle != null) handle.close();
            throw new IOException("Impacto session is active", failure);
        }
    }
    String pending(long now) throws IOException {
        File lease = ImpactoFiles.child(root, "pending");
        if (!lease.exists()) return null;
        if (!lease.isFile() || lease.length() > 128) throw new IOException("Invalid dispatch lease");
        String[] values = new String(Files.readAllBytes(lease.toPath()), StandardCharsets.US_ASCII).split("\n");
        if (values.length != 2 || !values[0].matches("[a-f0-9-]{36}")) throw new IOException("Invalid dispatch lease");
        try {
            long expires = Long.parseLong(values[1]);
            if (expires < now || expires > now + DISPATCH_TTL_MS) {
                Files.delete(lease.toPath());
                return null;
            }
        } catch (NumberFormatException invalid) { throw new IOException("Invalid dispatch lease", invalid); }
        return values[0];
    }
    void dispatch(String token, long now) throws IOException {
        if (pending(now) != null) throw new IOException("A launch is already pending");
        File temp = ImpactoFiles.child(root, "pending.tmp");
        Files.write(temp.toPath(), (token + "\n" + (now + DISPATCH_TTL_MS)).getBytes(StandardCharsets.US_ASCII));
        Files.move(temp.toPath(), ImpactoFiles.child(root, "pending").toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    void claim(File session, long now) throws IOException {
        String token = session.getName();
        String pending = pending(now);
        File claimed = ImpactoFiles.child(session, ".claimed");
        if (!token.equals(pending) && !(pending == null && claimed.isFile())) {
            throw new IOException("Expired or replaced launch dispatch");
        }
        Files.write(claimed.toPath(), token.getBytes(StandardCharsets.US_ASCII));
        clear(token);
    }
    void clear(String token) throws IOException {
        File lease = ImpactoFiles.child(root, "pending");
        if (lease.isFile() && lease.length() <= 128) {
            String text = new String(Files.readAllBytes(lease.toPath()), StandardCharsets.US_ASCII);
            if (text.startsWith(token + "\n")) Files.delete(lease.toPath());
        }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            try { lock.release(); } finally { handle.close(); }
        } finally {
            synchronized (ImpactoSession.class) { OWNED.remove(root); }
        }
    }
}
