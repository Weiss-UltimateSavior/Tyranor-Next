package com.tyranor.runtime.systemnnn;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class StoryPlaneInvalidationTest {
    @Test public void skippedDrawStillWakesCompositor(){
        android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(()->verify(instrumentation.getTargetContext()));
    }
    private static void check(boolean value,String message){
        if(!value)throw new AssertionError(message);
    }
    private static final class Host extends android.widget.FrameLayout implements StoryPlaneView.Host {
        int changes;
        Host(android.content.Context context){super(context);}
        @Override public void invalidateStoryPlane(){changes++;}
    }
    static void verify(android.content.Context context){
        Host host=new Host(context);
        StoryPlaneView control=new StoryPlaneView(context);
        host.addView(control);control.layout(0,0,32,32);
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(32,32,android.graphics.Bitmap.Config.ARGB_8888);
        control.draw(new android.graphics.Canvas(bitmap));
        int before=host.changes;
        control.invalidate();
        control.invalidate(); // No intervening draw: HWUI may coalesce this invalidation.
        control.invalidate(new android.graphics.Rect(0,0,10,10));
        control.invalidate(0,0,10,10);
        check(host.changes>=before+4,"skipped draw swallowed compositor wakeups");

        DialogueTextView text=new DialogueTextView(context);
        host.addView(text);text.layout(0,0,32,32);
        before=host.changes;
        text.invalidate();text.invalidate();
        text.invalidate(0,0,10,10);text.invalidate(new android.graphics.Rect(0,0,10,10));
        check(host.changes>=before+4,"text invalidation depended on HWUI flags");

        host.removeView(control);
        before=host.changes;
        control.invalidate();
        check(host.changes==before,"detached child still wakes the old compositor");
        DialoguePanel dialogue=new DialoguePanel(context,view->{});
        check(dialogue.body.getLayerType()==android.view.View.LAYER_TYPE_NONE&&
            dialogue.speaker.getLayerType()==android.view.View.LAYER_TYPE_NONE,"story text has a redundant software cache");
        bitmap.recycle();
    }
}
