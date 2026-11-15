package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class GameButtonTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder(
        InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());

    @Test public void recoveryButtonRequiresAReachableHitRegion() throws Exception {
        Path init=folder.newFolder("init").toPath();
        String setup="notButtonSizeX 4\nnotButtonSizeY 4\nnotButtonCheckPicX 8\n"+
            "rectButtonPrintMode 0\n"+
            "maskedButtonSizeX 4\nmaskedButtonSizeY 4\nmaskedButtonCheckDeltaX 3\n";
        Files.write(init.resolve("codecerror.xtx"),setup.getBytes(StandardCharsets.US_ASCII));
        GameUiAssets.Config cfg=GameUiAssets.readConfig(folder.getRoot(),"codecerror");
        ButtonArt.Setup skip=new ButtonArt.Setup(cfg,"notButton",null,false);
        GameButton.Spec skipButton=new GameButton.Spec(skip);
        GameButton.Spec inheritedExit=new GameButton.Spec(new ButtonArt.Setup(cfg,"exitButton",skip,false));
        GameButton.Spec rectangular=new GameButton.Spec(new ButtonArt.Setup(cfg,"rectButton",skip,false));
        GameButton.Spec masked=new GameButton.Spec(new ButtonArt.Setup(cfg,"maskedButton",null,false));
        Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);
        try{
            assertFalse(skipButton.hasTouchTarget(bitmap,0,0,8,8));
            assertFalse(inheritedExit.hasTouchTarget(bitmap,0,0,8,8));
            assertTrue(rectangular.hasTouchTarget(bitmap,0,0,8,8));
            assertFalse(rectangular.hasTouchTarget(bitmap,8,0,8,8));
            assertTrue(rectangular.hasTouchTarget(bitmap,-3,0,8,8));
            assertFalse(masked.hasTouchTarget(bitmap,0,0,8,8));
            bitmap.setPixel(1,0,0xff000000);
            assertFalse(masked.hasTouchTarget(bitmap,0,0,8,8));
            bitmap.setPixel(0,0,0x01000000);
            assertTrue(masked.hasTouchTarget(bitmap,0,0,8,8));
            assertFalse(masked.hasTouchTarget(bitmap,5,0,8,8));
            assertFalse(masked.hasTouchTarget(bitmap,-4,0,8,8));
        }finally{
            bitmap.recycle();
        }
    }
}
