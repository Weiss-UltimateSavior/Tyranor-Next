package com.tyranor.runtime.systemnnn;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PortraitAnimationTest {
    @Test public void waitingBlinkDoesNotRequestDisplayFrames(){
        assertFalse(DialogueLayout.portraitMoving(0,true,4,true,12,-12));
        assertFalse(DialogueLayout.portraitMoving(0,true,4,true,12,-1));
        assertTrue(DialogueLayout.portraitMoving(0,true,4,true,12,0));
        assertTrue(DialogueLayout.portraitMoving(0,true,4,false,12,4));
    }
    @Test public void finishedOrStaticPortraitsStopAnimating(){
        assertFalse(DialogueLayout.portraitMoving(0,true,4,false,0,4));
        assertFalse(DialogueLayout.portraitMoving(1,true,4,true,12,0));
        assertFalse(DialogueLayout.portraitMoving(0,true,1,true,12,0));
        assertFalse(DialogueLayout.portraitMoving(0,false,4,true,12,0));
    }
}
