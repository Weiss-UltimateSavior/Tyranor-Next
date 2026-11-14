package com.tyranor.runtime.systemnnn;

import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class ChartViewTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void detectedLegacyChartUsesItsOwnSetupAndKeepsTheInternalPreviewMode() throws Exception {
        Path init=folder.newFolder("init").toPath(),init2=folder.newFolder("init2").toPath();
        Files.write(init.resolve("chart.xtx"),"backScriptFlag 1\nbackScriptName old_plain\n".getBytes(StandardCharsets.UTF_8));
        byte[] legacy=("backScriptFlag 1\nbackScriptName old_chart\npageNumberVarName old_page\n"+
            "changePageRestartScriptFlag 1\n").getBytes(StandardCharsets.US_ASCII);
        for(int i=0;i<legacy.length;i++)legacy[i]^=(byte)255;
        Files.write(init2.resolve("chart.fxf"),legacy);
        Files.write(init.resolve("selectChart.xtx"),"backScriptFlag 1\nbackScriptName modern_chart\npageNumberVarName modern_page\n".getBytes(StandardCharsets.UTF_8));
        GameTextDecoder decoder=GameTextDecoder.load(folder.getRoot());
        UiPreviewPool.Request oldChart=UiPreviewPool.Request.read(GameUiAssets.readChartConfig(folder.getRoot(),decoder,true),GameRunner.Modal.SELECT_CHART,2);
        UiPreviewPool.Request modernChart=UiPreviewPool.Request.read(GameUiAssets.readChartConfig(folder.getRoot(),decoder,false),GameRunner.Modal.SELECT_CHART,2);
        assertNotNull(oldChart);assertNotNull(modernChart);
        assertEquals("old_chart",oldChart.script);assertEquals("old_page",oldChart.variable);assertTrue(oldChart.restartOnPage);
        assertEquals("modern_chart",modernChart.script);assertEquals("modern_page",modernChart.variable);assertFalse(modernChart.restartOnPage);
        assertEquals(34,oldChart.mode);assertEquals(34,modernChart.mode);assertEquals(2,oldChart.page);
        assertFalse(oldChart.sameScript(modernChart));
    }

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
