package com.tyranor.runtime.systemnnn;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DialogueMarkupTest {
    @Test public void rubyAndEmphasisDoNotConsumeDialogueTicks(){
        String source="漢字#［２かんじ］語#強・］。";
        DialogueMarkup body=DialogueMarkup.parse(source,false,true);
        assertEquals("漢字語。",body.text);
        assertEquals(4,body.glyphCount);
        assertEquals(2,body.annotations.size());
        DialogueMarkup.Annotation ruby=body.annotations.get(0),mark=body.annotations.get(1);
        assertFalse(ruby.emphasis);
        assertEquals(0,ruby.start);
        assertEquals(2,ruby.end);
        assertEquals("かんじ",ruby.label);
        assertTrue(mark.emphasis);
        assertEquals("・",mark.label);
        assertEquals("じ",body.slice(1,2).annotations.get(0).label);

        DialogueMarkup disabled=DialogueMarkup.parse(source,false,false);
        assertEquals(body.text,disabled.text);
        assertTrue(disabled.annotations.isEmpty());
    }

    @Test public void oneByteCommandsKeepDifferentMarksBoundToTheirCharacters(){
        DialogueMarkup body=DialogueMarkup.parse("AB#[2ab]CD#.^*]",true,true);
        assertEquals("ABCD",body.text);
        assertEquals("^",body.slice(2,3).annotations.get(0).label);
        assertEquals("*",body.slice(3,4).annotations.get(0).label);
    }

    @Test public void rangesCountCodePointsAndStayWithinTheOriginalLine(){
        DialogueMarkup supplementary=DialogueMarkup.parse("𠀀字#［2よみ］",false,true);
        assertEquals(2,supplementary.glyphCount);
        assertEquals(3,supplementary.annotations.get(0).end);
        DialogueMarkup acrossLines=DialogueMarkup.parse("甲\n乙#［２かな］",false,true);
        assertEquals("甲\n乙",acrossLines.text);
        assertTrue(acrossLines.annotations.isEmpty());
        String malformed="A # unknown #［xbroken #強no\nend］";
        assertEquals(malformed,DialogueMarkup.parse(malformed,false,true).text);
    }
}
