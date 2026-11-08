package android.util;

/** Allows only scoped e/i diagnostics; every other call retains mockable-android behavior. */
public final class Log {
    public static final int VERBOSE = 2;
    public static final int DEBUG = 3;
    public static final int INFO = 4;
    public static final int WARN = 5;
    public static final int ERROR = 6;
    public static final int ASSERT = 7;

    private static volatile Object activeFixture;

    private Log() {}

    /** Sends e/i calls to stderr until closed; unscoped tests still fail on Android logging. */
    public static synchronized AutoCloseable fixtureScope() {
        if (activeFixture != null) throw new IllegalStateException("A logging fixture is already active");
        Object scope = new Object();
        activeFixture = scope;
        return () -> {
            synchronized (Log.class) {
                if (activeFixture == scope) activeFixture = null;
            }
        };
    }

    /** Records a scoped error without hiding the repository failure. */
    public static int e(String tag, String message) { return record("e", tag, message, null); }

    /** Records a scoped error and its complete exception stack. */
    public static int e(String tag, String message, Throwable error) { return record("e", tag, message, error); }

    /** Records an informational diagnostic only in an explicit fixture scope. */
    public static int i(String tag, String message) { return record("i", tag, message, null); }

    /** Records an informational diagnostic and its exception only in an explicit fixture scope. */
    public static int i(String tag, String message, Throwable error) { return record("i", tag, message, error); }

    /** Retains the standard mock failure in every scope. */
    public static int v(String tag, String message) { throw notMocked("v"); }

    /** Retains the standard mock failure in every scope. */
    public static int v(String tag, String message, Throwable error) { throw notMocked("v"); }

    /** Retains the standard mock failure in every scope. */
    public static int d(String tag, String message) { throw notMocked("d"); }

    /** Retains the standard mock failure in every scope. */
    public static int d(String tag, String message, Throwable error) { throw notMocked("d"); }

    /** Retains the standard mock failure in every scope. */
    public static int w(String tag, String message) { throw notMocked("w"); }

    /** Retains the standard mock failure in every scope. */
    public static int w(String tag, String message, Throwable error) { throw notMocked("w"); }

    /** Retains the standard mock failure in every scope. */
    public static int w(String tag, Throwable error) { throw notMocked("w"); }

    /** Retains the standard mock failure in every scope. */
    public static int wtf(String tag, String message) { throw notMocked("wtf"); }

    /** Retains the standard mock failure in every scope. */
    public static int wtf(String tag, Throwable error) { throw notMocked("wtf"); }

    /** Retains the standard mock failure in every scope. */
    public static int wtf(String tag, String message, Throwable error) { throw notMocked("wtf"); }

    /** Retains the standard mock failure in every scope. */
    public static boolean isLoggable(String tag, int level) { throw notMocked("isLoggable"); }

    /** Retains the standard mock failure in every scope. */
    public static int println(int priority, String tag, String message) { throw notMocked("println"); }

    /** Retains the standard mock failure in every scope. */
    public static String getStackTraceString(Throwable error) { throw notMocked("getStackTraceString"); }

    private static int record(String level, String tag, String message, Throwable error) {
        if (activeFixture == null) throw notMocked(level);
        System.err.println(level.toUpperCase(java.util.Locale.ROOT) + "/" + tag + ": " + message);
        if (error != null) error.printStackTrace(System.err);
        return 0;
    }

    private static RuntimeException notMocked(String method) {
        return new RuntimeException("Method " + method + " in android.util.Log not mocked. "
                + "See https://developer.android.com/r/studio-ui/build/not-mocked for details.");
    }
}
