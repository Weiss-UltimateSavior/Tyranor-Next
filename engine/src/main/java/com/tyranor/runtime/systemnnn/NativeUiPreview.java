package com.tyranor.runtime.systemnnn;
import android.graphics.Bitmap;
import android.view.Surface;
final class NativeUiPreview {
    static native long chartLayerRevision(long handle,int center);
    static native int chartLayerMask(long handle,int center);
    static native int chartLayers(long handle,int center,Bitmap before,Bitmap after,Bitmap foreground);
    static native boolean chartNeedsComposite(long handle,int center);
    static native boolean chartComposite(long handle,int center,Bitmap base,Bitmap centerPlane,Bitmap content,Bitmap output);
    static native long[] chartTextureIds(long handle,int tiles,int center);
    static native int[] chartTexture(long handle,int slot,boolean transparent);
    static {System.loadLibrary("systemnnn");}
    static native long create(byte[] root,long runtime,long assetPeer);
    static native boolean reset(long handle,byte[] script,int mode,byte[] variable,int page,boolean restartOnPage,boolean loop,long runtime,boolean speculative);
    static native boolean claim(long handle,long runtime);
    static native void surface(long handle,Surface surface);
    static native void savedScreen(long handle,int[] pixels,int width,int height);
    static native void page(long handle,int page);
    static native void prime(long handle);
    static native void capture(long handle,Bitmap bitmap);
    static native void tick(long handle,int mouseX,int mouseY,boolean clicked,boolean held,int frames);
    static native void close(long handle);
    static native long createWipe(Bitmap before,Bitmap after,int color,int width,int height);
    static native void drawWipe(long handle,Bitmap target,int type,int frame,int frames);
    static native void closeWipe(long handle);
}
