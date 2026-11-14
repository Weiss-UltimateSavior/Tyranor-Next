package com.tyranor.runtime.systemnnn;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChartViewTest {
    private static ChartView chart(Integer minimum){
        int[] geometry=new int[minimum==null?27:29];
        geometry[0]=minimum==null?0:minimum;geometry[1]=minimum==null?6700:7000;
        geometry[2]=7300;geometry[13]=300;geometry[20]=1;geometry[25]=7300;
        if(minimum!=null)geometry[27]=minimum;
        byte[][] text=new byte[5][];Arrays.fill(text,new byte[0]);
        return new ChartView(geometry,text,raw->"");
    }
    @Test public void existingPayloadPreservesModernScrollGeometry(){
        ChartView modern=chart(null);
        assertEquals(0,modern.minScroll);assertEquals(0,modern.clampScroll(-1));
        assertEquals(24,modern.miniLength(600));assertEquals(275,modern.miniOffset(6700,600));
    }
    @Test public void signedScrollMapsBothMinimapEndpoints(){
        ChartView legacy=chart(-300);
        assertEquals(-300,legacy.clampScroll(-301));assertEquals(-1,legacy.clampScroll(-1));
        assertEquals(7000,legacy.clampScroll(7001));
        int travel=legacy.miniHeight-legacy.miniLength(600);
        assertEquals(0,legacy.miniOffset(-300,600));assertEquals(travel,legacy.miniOffset(7000,600));
        assertEquals(-300,legacy.scrollFromMini(0,600));assertEquals(7000,legacy.scrollFromMini(travel,600));
    }
    @Test(expected=IllegalArgumentException.class) public void invertedLimitsAreRejected(){chart(7001);}
}
