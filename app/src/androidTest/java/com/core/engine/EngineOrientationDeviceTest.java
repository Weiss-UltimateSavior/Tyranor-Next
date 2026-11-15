package com.core.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/** Checks the installed, merged manifest, including inherited app manifest declarations. */
@RunWith(AndroidJUnit4.class)
public final class EngineOrientationDeviceTest {
    private static final String[] HOSTS = {
            "com.akira.tyranoemu.remote.Kirikiroid134",
            "com.akira.tyranoemu.remote.Kirikiroid139",
            "com.core.krkrsdl3.Krkrsdl3Activity",
            "com.core.tyrano.TyranoActivity",
            "com.core.rpgmaker.RpgMakerActivity",
            "com.akira.tyranoemu.remote.ArtemisActivityV1",
            "com.akira.tyranoemu.remote.ArtemisActivityV2",
            "com.akira.tyranoemu.remote.ArtemisActivityV6",
            "com.akira.tyranoemu.remote.ArtemisActivityClean",
            "com.core.siglus.SiglusActivity",
            "com.core.ethornell.EthornellActivity",
            "com.core.cmvs.CmvsActivity",
            "com.core.fvp.FvpActivity",
            "com.core.games.FramebufferGameActivity",
            "com.core.xsys35.Xs35Activity",
            "com.core.xsys4.Xs4Activity",
            "com.core.xsys4.Xs4ManualActivity",
            "com.tyranor.runtime.systemnnn.PlayerActivity",
            "com.yuri.onscripter.ONScripter",
            "com.ies_net.artemis.VideoViewActivity",
    };

    @Test public void installedPlayersAndVideoKeepFixedLandscapeOnLargeScreens() throws Exception {
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("Dedicated startup acceptance package required",
                "com.tyranor.next.startuptest".equals(app.getPackageName()));
        assumeTrue("Explicit device-test opt-in required", "true".equals(
                InstrumentationRegistry.getArguments().getString("startup_test")));
        PackageManager manager = app.getPackageManager();
        for (String host : HOSTS) {
            ComponentName component = new ComponentName(app.getPackageName(), host);
            ActivityInfo info = manager.getActivityInfo(component, 0);
            assertEquals(host + " allows sensor or reverse rotation",
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, info.screenOrientation);
            if (Build.VERSION.SDK_INT >= 31) {
                PackageManager.Property compatibility = manager.getProperty(
                        "android.window.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY", component);
                assertTrue(host + " misses the activity-scoped Android 16 compatibility opt-out",
                        compatibility.getBoolean());
            }
        }
        ActivityInfo main = manager.getActivityInfo(new ComponentName(app.getPackageName(),
                "com.tyranor.next.MainActivity"), 0);
        assertEquals("Player policy must not lock the app's main pages",
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, main.screenOrientation);
    }
}
