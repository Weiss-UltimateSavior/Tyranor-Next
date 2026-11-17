package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
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

    @Test public void clippedSpriteFrameRefreshesAfterAtlasChanges() {
        Bitmap atlas=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888);
        Bitmap output=Bitmap.createBitmap(6,4,Bitmap.Config.ARGB_8888);
        try{
            // The 3-pixel frame extends beyond the atlas, leaving 2 pixels to scale.
            SpriteAnimation sprite=new SpriteAnimation(2,1,3,2,1,1,1,true);
            Canvas canvas=new Canvas(output);
            Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
            RectF dest=new RectF(0,0,6,4);

            atlas.eraseColor(Color.RED);
            assertTrue(sprite.draw(canvas,atlas,dest,paint,0,255));
            assertEquals(Color.RED,output.getPixel(3,2));
            assertEquals(Color.TRANSPARENT,output.getPixel(4,2));

            atlas.eraseColor(Color.BLUE);
            output.eraseColor(Color.TRANSPARENT);
            assertTrue(sprite.draw(canvas,atlas,dest,paint,0,255));
            assertEquals(Color.BLUE,output.getPixel(3,2));
            assertEquals(Color.TRANSPARENT,output.getPixel(4,2));
        }finally{
            output.recycle();
            atlas.recycle();
        }
    }
}
