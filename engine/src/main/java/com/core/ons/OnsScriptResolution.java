package com.core.ons;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Parses or detects the logical stage resolution specified in the ONScripter game script
 * before or during engine initialization.
 */
public final class OnsScriptResolution {
    private static final String TAG = "OnsScriptResolution";

    public static final int DEFAULT_WIDTH = 640;
    public static final int DEFAULT_HEIGHT = 480;

    public final int width;
    public final int height;

    public OnsScriptResolution(int width, int height) {
        this.width = width > 0 ? width : DEFAULT_WIDTH;
        this.height = height > 0 ? height : DEFAULT_HEIGHT;
    }

    public static OnsScriptResolution detect(String rootPath) {
        if (rootPath == null || rootPath.trim().isEmpty()) {
            return new OnsScriptResolution(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }
        return detect(new File(rootPath));
    }

    public static OnsScriptResolution detect(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return new OnsScriptResolution(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }

        File[] candidates = new File[] {
                new File(dir, "0.txt"),
                new File(dir, "00.txt"),
                new File(dir, "nscript.dat")
        };

        for (File f : candidates) {
            if (f.exists() && f.isFile()) {
                OnsScriptResolution res = parseFromFile(f);
                if (res != null) {
                    return res;
                }
            }
        }
        return new OnsScriptResolution(DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    static OnsScriptResolution parseFromFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        boolean isEncryptedDat = file.getName().toLowerCase(Locale.ROOT).endsWith(".dat");
        byte[] buffer = new byte[8192];
        int readBytes = 0;
        try (InputStream in = new FileInputStream(file)) {
            readBytes = in.read(buffer);
        } catch (java.io.IOException | RuntimeException t) {
            Log.w(TAG, "Failed to read header of " + file.getName(), t);
            return null;
        }

        if (readBytes <= 0) return null;

        if (isEncryptedDat) {
            for (int i = 0; i < readBytes; i++) {
                buffer[i] = (byte) (buffer[i] ^ 0x84);
            }
        }

        String content = new String(buffer, 0, readBytes, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return parseFromText(content);
    }

    static OnsScriptResolution parseFromText(String content) {
        if (content == null || content.isEmpty()) return null;

        // ONScripter 的 `;mode###` 系列：数字即横向像素，纵向一般按 4:3 推出
        //（`;mode1280` → 1280×960，`;mode960` → 960×720，`;mode640` → 640×480）。
        // 例外是 `;mode1920`：ONS 脚本里对应 1920×1080（16:9），不套 4:3。
        // 这里只是「native 尺寸尚未可取时」的兜底猜测（`provideProxySurface` 优先用
        // `nativeGetWidth/Height`）；仓内没有 `;mode1280` 分辨率变化的设备或样本锚点，
        // 若未来发现某脚本族另有约定，应以 native 回报为准并在此补充证据。
        if (content.contains(";mode1920")) return new OnsScriptResolution(1920, 1080);
        if (content.contains(";mode1280")) return new OnsScriptResolution(1280, 960);
        if (content.contains(";mode1024")) return new OnsScriptResolution(1024, 768);
        if (content.contains(";mode960")) return new OnsScriptResolution(960, 720);
        if (content.contains(";mode800")) return new OnsScriptResolution(800, 600);
        if (content.contains(";mode640")) return new OnsScriptResolution(640, 480);
        if (content.contains(";mode400")) return new OnsScriptResolution(400, 300);

        return null;
    }
}
