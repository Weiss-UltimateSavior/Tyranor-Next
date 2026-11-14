package com.tyranor.runtime.systemnnn;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StoryPlaneGateTest {
    @Test public void nativePanelPointerKeepsOutsideCanvasCoordinates(){
        assertEquals(200,PlayerActivity.pointerCoordinate(500,100,800,1600,false));
        assertEquals(-1,PlayerActivity.pointerCoordinate(99.5f,100,800,1600,false));
        assertEquals(800,PlayerActivity.pointerCoordinate(1700,100,800,1600,false));
        assertEquals(0,PlayerActivity.pointerCoordinate(99.5f,100,800,1600,true));
        assertEquals(799,PlayerActivity.pointerCoordinate(1700,100,800,1600,true));
        assertEquals(Integer.MAX_VALUE,PlayerActivity.pointerCoordinate(Float.MAX_VALUE,Integer.MIN_VALUE,800,1,false));
        assertEquals(Integer.MIN_VALUE,PlayerActivity.pointerCoordinate(-Float.MAX_VALUE,Integer.MAX_VALUE,800,1,false));
    }
    @Test public void onlyActuallyPresentedFullTextAcknowledgesDialogue(){
        int[] expected={3,12,1,0,0,0};
        int[] partial=DialogueLayout.composePlaneKey(expected,false,0,false);
        int[] complete=DialogueLayout.composePlaneKey(expected,false,0,true);
        assertTrue(DialogueLayout.planeKeyMatches(partial,expected));
        assertEquals(DialogueLayout.GATE_HOLD,DialogueLayout.evaluatePlaneGate(partial,expected,true,true,false,0));
        assertEquals(DialogueLayout.GATE_TIMED_OUT,DialogueLayout.evaluatePlaneGate(partial,expected,true,true,false,5000));
        assertEquals(DialogueLayout.GATE_MATCHED,DialogueLayout.evaluatePlaneGate(complete,expected,true,true,false,5000));
        int[] hidden=DialogueLayout.composePlaneKey(expected,true,0,true);
        assertEquals(0,hidden[5]&DialogueLayout.PLANE_TEXT_COMPLETE);
        assertFalse(DialogueLayout.planeKeyMatches(hidden,expected));
    }
    @Test public void choicesStillUseTheirOwnPresentedIdentity(){
        int[] choice={4,0,0,0,27,0};
        assertEquals(DialogueLayout.GATE_MATCHED,DialogueLayout.evaluatePlaneGate(choice,choice,true,true,false,0));
        assertEquals(DialogueLayout.GATE_HOLD,DialogueLayout.evaluatePlaneGate(new int[]{4,0,0,0,26,0},choice,true,true,false,0));
    }
    @Test public void originalControlsRespectPrintingAndReplay(){
        for(GameStoryControlsModel.Action action:GameStoryControlsModel.Action.values()){
            assertTrue(GameStoryControls.clickableWhileTyping(action,false));
            assertEquals(action==GameStoryControlsModel.Action.FILM_SKIP,GameStoryControls.clickableWhileTyping(action,true));
            boolean save=action==GameStoryControlsModel.Action.SAVE||action==GameStoryControlsModel.Action.LOAD||
                action==GameStoryControlsModel.Action.QUICK_SAVE||action==GameStoryControlsModel.Action.QUICK_LOAD;
            assertEquals(!save,GameStoryControls.allowedInReplay(action,true));
            assertTrue(GameStoryControls.allowedInReplay(action,false));
        }
    }
}
