package com.core.ethornell;

import android.content.Context;
import android.os.Process;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.NoSuchFileException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/** Durable dispatch metadata, NOT a native user-data writer lock. Every operation fails closed. */
public final class EthornellDispatchRegistry {
    public enum Stage { ISSUED, CLAIMED, TERMINAL, REVOKED }
    public static final class Record {
        public final String token, identity, startTime;
        public final int pid;
        public final Stage stage;
        private Record(String token, String identity, int pid, String startTime, Stage stage) {
            this.token = token; this.identity = identity; this.pid = pid; this.startTime = startTime; this.stage = stage;
        }
    }
    private static final int LIMIT = 4096, MAX_BYTES = 40_000, MAGIC = 0x45444731;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();
    private EthornellDispatchRegistry() {}
    private interface Operation<T> { T run(Path root) throws IOException; }

    public static String issue(File filesDir, String identity) throws IOException {
        validateIdentity(filesDir, identity);
        return locked(filesDir, root -> {
            List<Record> records = scan(root);
            boolean removed = false;
            for (Record record : records) {
                if (record.stage == Stage.TERMINAL || record.stage == Stage.REVOKED) {
                    Files.delete(recordPath(root, record.token)); removed = true;
                }
            }
            if (removed) syncDirectory(root);
            if (records.stream().filter(record -> record.stage == Stage.ISSUED || record.stage == Stage.CLAIMED).count() >= LIMIT)
                throw new IOException("Dispatch record limit");
            byte[] bytes = new byte[16]; RANDOM.nextBytes(bytes);
            StringBuilder token = new StringBuilder(32);
            for (byte b : bytes) { token.append(Character.forDigit((b & 255) >>> 4, 16)); token.append(Character.forDigit(b & 15, 16)); }
            String value = token.toString();
            if (Files.exists(recordPath(root, value), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dispatch token collision");
            write(root, new Record(value, identity, 0, "", Stage.ISSUED));
            return value;
        });
    }
    public static boolean claim(File filesDir, String token, String identity, int pid, String startTime) throws IOException {
        validateToken(token); validateIdentity(filesDir, identity); validateOwner(pid, startTime);
        return locked(filesDir, root -> {
            Record previous = read(root, token);
            if (previous == null || previous.stage != Stage.ISSUED || !previous.identity.equals(identity)) return false;
            write(root, new Record(token, identity, pid, startTime, Stage.CLAIMED)); return true;
        });
    }
    public static Record status(File filesDir, String token) throws IOException {
        validateToken(token); return locked(filesDir, root -> read(root, token));
    }
    public static List<Record> records(File filesDir) throws IOException { return locked(filesDir, EthornellDispatchRegistry::scan); }
    /** No AM-list absence heuristic: /proc evidence unavailable means IOException, never "dead". */
    public static boolean isOwnerAlive(Context context, Record record) throws IOException {
        return ownerAlive(record, Process.myUid(), context.getPackageName() + ":ethornell", EthornellDispatchRegistry::procText);
    }
    interface OwnerProbe { String read(int pid, String name) throws IOException; }
    static boolean ownerAlive(Record record, int expectedUid, String expectedName, OwnerProbe probe) throws IOException {
        if (record == null || (record.stage != Stage.CLAIMED && record.stage != Stage.TERMINAL))
            throw new IOException("Dispatch owner required");
        String start;
        try { start = parseStart(probe.read(record.pid, "stat")); }
        catch (NoSuchFileException gone) { return false; }
        if (!record.startTime.equals(start)) return false;
        String status = probe.read(record.pid, "status");
        String uid = null;
        for (String line : status.split("\n")) if (line.startsWith("Uid:")) uid = line.substring(4).trim().split("\\s+")[0];
        if (!Integer.toString(expectedUid).equals(uid)) throw new IOException("Dispatch UID evidence mismatch");
        String command = probe.read(record.pid, "cmdline").split("\u0000", -1)[0];
        if (!expectedName.equals(command)) throw new IOException("Dispatch process evidence mismatch");
        try { return record.startTime.equals(parseStart(probe.read(record.pid, "stat"))); }
        catch (NoSuchFileException gone) { return false; }
    }
    /** Recovery without timeout: only positive owner-death evidence retires a claimed dispatch. */
    public static boolean retireDeadClaim(Context context, String token) throws IOException {
        return retireDeadClaim(context.getFilesDir(), token, Process.myUid(), context.getPackageName() + ":ethornell",
                EthornellDispatchRegistry::procText);
    }
    static boolean retireDeadClaim(File filesDir, String token, int uid, String processName, OwnerProbe probe) throws IOException {
        validateToken(token);
        return locked(filesDir, root -> {
            Record previous = read(root, token);
            if (previous == null || previous.stage == Stage.TERMINAL || previous.stage == Stage.REVOKED) return true;
            if (previous.stage != Stage.CLAIMED || ownerAlive(previous, uid, processName, probe)) return false;
            write(root, new Record(previous.token, previous.identity, previous.pid, previous.startTime, Stage.TERMINAL));
            return true;
        });
    }
    static String processStart(int pid) throws IOException {
        return parseStart(procText(pid, "stat"));
    }
    private static String parseStart(String stat) throws IOException {
        int end = stat.lastIndexOf(") ");
        if (end < 0) throw new IOException("Invalid process stat");
        String[] fields = stat.substring(end + 2).split(" ");
        if (fields.length <= 19 || !fields[19].matches("[0-9]{1,24}")) throw new IOException("Invalid process starttime");
        return fields[19];
    }
    private static String procText(int pid, String name) throws IOException {
        Path path = new File("/proc/" + pid + "/" + name).toPath();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.allocate(16385);
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) break;
            if (bytes.position() > 16384) throw new IOException("Process evidence limit");
            return new String(bytes.array(), 0, bytes.position(), StandardCharsets.UTF_8);
        }
    }
    public static boolean revokeUnclaimed(File filesDir, String token, String identity) throws IOException {
        validateToken(token); validateIdentity(filesDir, identity);
        return locked(filesDir, root -> {
            Record previous = read(root, token);
            if (previous == null || previous.stage != Stage.ISSUED || !previous.identity.equals(identity)) return false;
            write(root, new Record(token, identity, 0, "", Stage.REVOKED)); return true;
        });
    }
    public static boolean finish(File filesDir, String token, String identity, int pid, String startTime) throws IOException {
        validateToken(token); validateIdentity(filesDir, identity); validateOwner(pid, startTime);
        return locked(filesDir, root -> {
            Record previous = read(root, token);
            if (previous == null || !previous.identity.equals(identity) || previous.pid != pid || !previous.startTime.equals(startTime)) return false;
            if (previous.stage == Stage.TERMINAL) return true; // matching-owner idempotency only
            if (previous.stage != Stage.CLAIMED) return false;
            write(root, new Record(token, identity, pid, startTime, Stage.TERMINAL)); return true;
        });
    }
    private static <T> T locked(File filesDir, Operation<T> operation) throws IOException {
        JVM_LOCK.lock();
        try {
            Path parent = filesDir.getCanonicalFile().toPath();
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Private root missing");
            Path root = parent.resolve("ethornell_dispatch");
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(root); } catch (FileAlreadyExistsException raced) { /* Validate below. */ }
                syncDirectory(parent);
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid dispatch root");
            Path lock = root.resolve("metadata.lock");
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid dispatch lock");
                channel.force(true); syncDirectory(root);
                return operation.run(root);
            }
        } finally { JVM_LOCK.unlock(); }
    }
    private static Path recordPath(Path root, String token) { return root.resolve(token + ".record"); }
    private static List<Record> scan(Path root) throws IOException {
        List<Record> result = new ArrayList<>(); int entries = 0;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(root)) {
            for (Path path : paths) {
                String name = path.getFileName().toString();
                if (name.equals("metadata.lock")) continue;
                if (++entries > LIMIT) throw new IOException("Dispatch scan limit");
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid dispatch entry");
                if (name.matches("[0-9a-f]{32}\\.record")) result.add(read(root, name.substring(0, 32)));
                else if (!name.matches("[0-9a-f]{32}\\.tmp")) throw new IOException("Unknown dispatch entry");
                // A crash before atomic rename leaves only an unissued temp, never a reusable token.
            }
        }
        return result;
    }
    private static Record read(Path root, String token) throws IOException {
        Path path = recordPath(root, token);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid dispatch record");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size <= 0 || size > MAX_BYTES) throw new IOException("Dispatch record size");
            ByteBuffer bytes = ByteBuffer.allocate((int) size);
            while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("Short dispatch record");
            if (channel.size() != size) throw new IOException("Changed dispatch record");
            DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes.array()));
            if (data.readInt() != MAGIC) throw new IOException("Dispatch record version");
            String storedToken = string(data), identity = string(data), start = string(data);
            int pid = data.readInt(), stage = data.readInt();
            if (!token.equals(storedToken) || stage < 0 || stage >= Stage.values().length || data.available() != 0) throw new IOException("Invalid dispatch fields");
            Stage value = Stage.values()[stage];
            if (identity.isEmpty()) throw new IOException("Empty dispatch identity");
            if (value == Stage.CLAIMED || value == Stage.TERMINAL) validateOwner(pid, start);
            else if (pid != 0 || !start.isEmpty()) throw new IOException("Unexpected dispatch owner");
            return new Record(token, identity, pid, start, value);
        }
    }
    private static void write(Path root, Record record) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(buffer);
        data.writeInt(MAGIC); string(data, record.token); string(data, record.identity); string(data, record.startTime);
        data.writeInt(record.pid); data.writeInt(record.stage.ordinal()); data.flush();
        Path destination = recordPath(root, record.token), temp = root.resolve(record.token + ".tmp");
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid dispatch destination");
        // Under the exclusive metadata lock no live writer can own this temp. A crash before
        // rename must not block later confirmed-dead retirement; never delete the durable record.
        if (Files.exists(temp, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(temp, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid dispatch temp");
            Files.delete(temp); syncDirectory(root);
        }
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.wrap(buffer.toByteArray()); while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(root);
    }
    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
    private static void string(DataOutputStream data, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8); data.writeInt(bytes.length); data.write(bytes);
    }
    private static String string(DataInputStream data) throws IOException {
        int size = data.readInt(); if (size < 0 || size > 32768 || size > data.available()) throw new IOException("Dispatch string size");
        byte[] bytes = new byte[size]; data.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static void validateToken(String token) throws IOException {
        if (token == null || !token.matches("[0-9a-f]{32}")) throw new IOException("Invalid dispatch token");
    }
    private static void validateOwner(int pid, String start) throws IOException {
        if (pid <= 0 || start == null || !start.matches("[0-9]{1,24}")) throw new IOException("Invalid dispatch owner");
    }
    private static void validateIdentity(File filesDir, String identity) throws IOException { EthornellPaths.userDataDir(filesDir, identity); }
}
