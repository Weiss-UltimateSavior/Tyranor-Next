package android.app;

import android.os.Process;
import java.util.Collections;
import java.util.List;

/** Supplies an explicit process identity to context-only JVM settings fixtures. */
public final class OnboardingActivityManager extends ActivityManager {
    private final String processName;

    public OnboardingActivityManager(String processName) {
        this.processName = processName;
    }

    @Override
    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        RunningAppProcessInfo info = new RunningAppProcessInfo();
        info.pid = Process.myPid();
        info.processName = processName;
        return Collections.singletonList(info);
    }
}
