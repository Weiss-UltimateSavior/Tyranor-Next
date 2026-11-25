package com.core.ons;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class OnsScriptResolutionTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testParseFromTextModes() {
        OnsScriptResolution res1920 = OnsScriptResolution.parseFromText(";mode1920\ndefsub test");
        assertNotNull(res1920);
        assertEquals(1920, res1920.width);
        assertEquals(1080, res1920.height);

        // `;mode1280` 按 4:3 推出 1280x960（与 1920 的 16:9 例外并存，见生产注释）。
        OnsScriptResolution res1280 = OnsScriptResolution.parseFromText(";mode1280\n*define");
        assertNotNull(res1280);
        assertEquals(1280, res1280.width);
        assertEquals(960, res1280.height);

        OnsScriptResolution res960 = OnsScriptResolution.parseFromText(";mode960\n*define");
        assertNotNull(res960);
        assertEquals(960, res960.width);
        assertEquals(720, res960.height);

        OnsScriptResolution res640 = OnsScriptResolution.parseFromText(";mode640\n*define");
        assertNotNull(res640);
        assertEquals(640, res640.width);
        assertEquals(480, res640.height);

        OnsScriptResolution res1024 = OnsScriptResolution.parseFromText(";mode1024\n*define");
        assertNotNull(res1024);
        assertEquals(1024, res1024.width);
        assertEquals(768, res1024.height);

        OnsScriptResolution res800 = OnsScriptResolution.parseFromText(";mode800\n*define");
        assertNotNull(res800);
        assertEquals(800, res800.width);
        assertEquals(600, res800.height);

        OnsScriptResolution res400 = OnsScriptResolution.parseFromText(";mode400\n*define");
        assertNotNull(res400);
        assertEquals(400, res400.width);
        assertEquals(300, res400.height);

        OnsScriptResolution resDefault = OnsScriptResolution.parseFromText("*define\ngame");
        assertNull(resDefault);
    }

    @Test
    public void testDetectFromPlainTextFile() throws Exception {
        File dir = tempFolder.newFolder("plain_game");
        File script0 = new File(dir, "0.txt");
        try (FileOutputStream out = new FileOutputStream(script0)) {
            out.write(";mode800\r\n*define\r\ngame\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        OnsScriptResolution res = OnsScriptResolution.detect(dir);
        assertEquals(800, res.width);
        assertEquals(600, res.height);
    }

    @Test
    public void testDetectFromEncryptedDat() throws Exception {
        File dir = tempFolder.newFolder("dat_game");
        File datFile = new File(dir, "nscript.dat");
        byte[] raw = ";mode1280\r\n*define\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] encrypted = new byte[raw.length];
        for (int i = 0; i < raw.length; i++) {
            encrypted[i] = (byte) (raw[i] ^ 0x84);
        }
        try (FileOutputStream out = new FileOutputStream(datFile)) {
            out.write(encrypted);
        }

        OnsScriptResolution res = OnsScriptResolution.detect(dir);
        assertEquals(1280, res.width);
        assertEquals(960, res.height);
    }

    @Test
    public void testDetectFallbackDefaultOnMissingOrInvalid() {
        OnsScriptResolution resNull = OnsScriptResolution.detect((String) null);
        assertEquals(OnsScriptResolution.DEFAULT_WIDTH, resNull.width);
        assertEquals(OnsScriptResolution.DEFAULT_HEIGHT, resNull.height);

        OnsScriptResolution resEmpty = OnsScriptResolution.detect(new File("non_existent_folder_xyz"));
        assertEquals(OnsScriptResolution.DEFAULT_WIDTH, resEmpty.width);
        assertEquals(OnsScriptResolution.DEFAULT_HEIGHT, resEmpty.height);
    }

    /** `00.txt` 与 `0.txt` 同属探测候选，前者命中时不得回落到默认值。 */
    @Test
    public void testDetectFromDoubleZeroScript() throws Exception {
        File dir = tempFolder.newFolder("double_zero_game");
        File script00 = new File(dir, "00.txt");
        try (FileOutputStream out = new FileOutputStream(script00)) {
            out.write(";mode960\r\n*define\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        OnsScriptResolution res = OnsScriptResolution.detect(dir);
        assertEquals(960, res.width);
        assertEquals(720, res.height);
    }

    /** 传入的是文件或空目录时不得抛异常，也不得读成非默认真值。 */
    @Test
    public void testDetectFallsBackForFileAndEmptyDirectory() throws Exception {
        File plainFile = tempFolder.newFile("not_a_dir.txt");
        OnsScriptResolution fromFile = OnsScriptResolution.detect(plainFile);
        assertEquals(OnsScriptResolution.DEFAULT_WIDTH, fromFile.width);
        assertEquals(OnsScriptResolution.DEFAULT_HEIGHT, fromFile.height);

        File emptyDir = tempFolder.newFolder("empty_game");
        OnsScriptResolution fromEmpty = OnsScriptResolution.detect(emptyDir);
        assertEquals(OnsScriptResolution.DEFAULT_WIDTH, fromEmpty.width);
        assertEquals(OnsScriptResolution.DEFAULT_HEIGHT, fromEmpty.height);
    }

    /** 构造器对非正数尺寸一律夹紧到默认值，避免出现 0 或负数舞台。 */
    @Test
    public void testConstructorClampsNonPositiveDimensions() {
        OnsScriptResolution zero = new OnsScriptResolution(0, 0);
        assertEquals(OnsScriptResolution.DEFAULT_WIDTH, zero.width);
        assertEquals(OnsScriptResolution.DEFAULT_HEIGHT, zero.height);

        OnsScriptResolution negative = new OnsScriptResolution(-1920, -1080);
        assertEquals(OnsScriptResolution.DEFAULT_WIDTH, negative.width);
        assertEquals(OnsScriptResolution.DEFAULT_HEIGHT, negative.height);

        OnsScriptResolution mixed = new OnsScriptResolution(1024, -1);
        assertEquals(1024, mixed.width);
        assertEquals(OnsScriptResolution.DEFAULT_HEIGHT, mixed.height);
    }
}
