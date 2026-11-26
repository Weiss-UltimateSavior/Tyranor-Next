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

        // ONScripter 只从脚本头首行的 `;mode###` 注释指令读取分辨率声明；
        // 仅检查首行完整 token，避免正文字符串包含 `;mode1920` 等文本覆盖首行模式。
        int start = 0;
        if (content.startsWith("\ufeff")) {
            start = 1;
        } else if (content.startsWith("\u00ef\u00bb\u00bf")) {
            start = 3;
        }
        int lineEnd = content.length();
        for (int i = start; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '\r' || ch == '\n') {
                lineEnd = i;
                break;
            }
        }
        String firstLine = content.substring(start, lineEnd).trim().toLowerCase(Locale.ROOT);
        if (!firstLine.startsWith(";")) {
            return null;
        }
        String[] tokens = firstLine.substring(1).split("[,;\\s]+");
        for (String rawToken : tokens) {
            switch (rawToken) {
                case "mode1920":
                    return new OnsScriptResolution(1920, 1080);
                case "mode1280":
                    return new OnsScriptResolution(1280, 960);
                case "mode1024":
                    return new OnsScriptResolution(1024, 768);
                case "mode960":
                    return new OnsScriptResolution(960, 720);
                case "mode800":
                    return new OnsScriptResolution(800, 600);
                case "mode640":
                    return new OnsScriptResolution(640, 480);
                case "mode400":
                    return new OnsScriptResolution(400, 300);
                default:
                    break;
            }
        }
        return null;
    }
}
