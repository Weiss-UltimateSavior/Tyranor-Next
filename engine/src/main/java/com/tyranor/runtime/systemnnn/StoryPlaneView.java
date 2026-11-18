package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;

/** Off-screen children notify their compositor even when HWUI coalesces invalidation. */
class StoryPlaneView extends View {
    interface Host { void invalidateStoryPlane(); }

    StoryPlaneView(Context context){super(context);}

    static void changed(View child){
        for(ViewParent parent=child.getParent();parent!=null;parent=parent.getParent()){
            if(parent instanceof Host){((Host)parent).invalidateStoryPlane();return;}
        }
    }

    @Override public void invalidate(){super.invalidate();changed(this);}
    @Override public void invalidate(Rect dirty){super.invalidate(dirty);changed(this);}
    @Override public void invalidate(int l,int t,int r,int b){super.invalidate(l,t,r,b);changed(this);}
}
