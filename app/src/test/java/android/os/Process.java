package android.os;

/** Keeps mockable-android behavior except for an explicitly scoped fixture PID. */
public final class Process {
    private static volatile PidFixture activeFixture;

    private Process() {}

    /** Enables only myPid for one test; closing the scope restores the original mock failure. */
    public static synchronized AutoCloseable fixtureScope(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("A fixture PID must be positive");
        if (activeFixture != null) throw new IllegalStateException("A process fixture is already active");
        PidFixture scope = new PidFixture(pid);
        activeFixture = scope;
        return () -> {
            synchronized (Process.class) {
                if (activeFixture == scope) activeFixture = null;
            }
        };
    }

    /** Returns the scoped fixture identity, or retains the standard not-mocked failure. */
    public static int myPid() {
        PidFixture fixture = activeFixture;
        if (fixture == null) throw notMocked("myPid");
        return fixture.pid;
    }

    /** Retains the standard mock failure, including while a PID fixture is active. */
    public static int myUid() { throw notMocked("myUid"); }

    /** Retains the standard mock failure, including while a PID fixture is active. */
    public static int myTid() { throw notMocked("myTid"); }

    /** Retains the standard mock failure; fixtures never terminate a host process. */
    public static void killProcess(int pid) { throw notMocked("killProcess"); }

    /** Retains the standard mock failure; fixtures never change host scheduling. */
    public static void setThreadPriority(int priority) { throw notMocked("setThreadPriority"); }

    /** Retains the standard mock failure; fixtures never change host scheduling. */
    public static void setThreadPriority(int tid, int priority) { throw notMocked("setThreadPriority"); }

    /** Retains the standard mock failure, including while a PID fixture is active. */
    public static int getThreadPriority(int tid) { throw notMocked("getThreadPriority"); }

    private static final class PidFixture {
        final int pid;

        PidFixture(int pid) { this.pid = pid; }
    }

    private static RuntimeException notMocked(String method) {
        return new RuntimeException("Method " + method + " in android.os.Process not mocked. "
                + "See https://developer.android.com/r/studio-ui/build/not-mocked for details.");
    }
}
