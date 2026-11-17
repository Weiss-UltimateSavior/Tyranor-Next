package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class GameRunnerChoiceTest {
    @Test public void choiceInputMatchesTheSelectionRatherThanOneFrameOrScriptOffset() {
        GameRunner.Frame shown = choiceFrame(4, 102, 33, 1);
        assertTrue(shown.sameChoiceSet(choiceFrame(4, 102, 33, 2)));
        assertFalse(shown.sameChoiceSet(choiceFrame(4, 102, 34, 1)));
        assertFalse(shown.sameChoiceSet(choiceFrame(4, 103, 33, 1)));
        assertFalse(shown.sameChoiceSet(choiceFrame(5, 102, 33, 1)));
    }

    private static GameRunner.Frame choiceFrame(long generation, int word, int choiceSet, int cursor) {
        return new GameRunner.Frame(
            NativeGame.CHOICE, 1280, 720, "story.spt", 10, word, "", "", DialogueMarkup.EMPTY,
            new String[]{"choice"}, false, 0, 0, 0, cursor, true, false, true, 0, 0,
            GameRunner.Frame.DRAW_NO_MESSAGE, 20, new String[0], new int[0], new int[0], 0,
            new int[0], new Bitmap[0], null, DialogueDecorations.EMPTY, false,
            new int[]{0, 0, 0, 0, choiceSet}, generation, 0, false
        );
    }
}
