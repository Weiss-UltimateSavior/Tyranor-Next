package com.tyranor.runtime.systemnnn;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assume.assumeNoException;

/** PC chart files and the paired Android progress wire use their declared layouts. */
public final class PcSystemSaveTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void chartProgressNamesUseAsciiCaseFolding(){
        check(PcSystemSave.isChartFile("MITA.DAT")&&PcSystemSave.isChartFile("TSUKA.DAT"),"PC chart names accept ASCII case");
        for(String name:new String[]{"m\u0131ta.dat","t\u017fuka.dat","tsu\u212aa.dat"})
            check(!PcSystemSave.isChartFile(name),"Unmanaged Unicode name is not a PC chart alias");
    }

    @Test public void modernChartUsesItsOriginalDirectoryAndPaddedLayout() throws Exception {
        chartProgress(folder.getRoot().toPath());
    }
    @Test public void legacyChartKeepsItsOwnDirectoryAndFourTailBytes() throws Exception {
        legacyChartProgress(folder.getRoot().toPath());
    }
    @Test public void progressWireRejectsUnknownShapesWithoutReplacingCommittedState() throws Exception {
        chartWireProgress(folder.getRoot().toPath());
    }
    @Test public void legacyChartRejectsSymbolicDirectoryAtOpenAndAfterOpen() throws Exception {
        Path root=folder.newFolder("linked-chart-game").toPath();
        Path init=Files.createDirectory(root.resolve("init")),sav=Files.createDirectory(root.resolve("sav"));
        Files.write(init.resolve("chart.xtx"),new byte[0]);
        Path outside=folder.newFolder("external-chart").toPath(),link=sav.resolve("mushiex");
        byte[] seen=new byte[10004],roads=new byte[10000];Arrays.fill(seen,(byte)19);Arrays.fill(roads,(byte)23);
        Files.write(outside.resolve("mita.dat"),seen);Files.write(outside.resolve("tsuka.dat"),roads);
        try{Files.createSymbolicLink(link,outside.toAbsolutePath());}
        catch(java.io.IOException|UnsupportedOperationException|SecurityException unavailable){assumeNoException(unavailable);return;}
        GameUiAssets.Config game=GameUiAssets.readConfig(root.toFile(),"game"),chart=GameUiAssets.readConfig(root.toFile(),"chart");
        boolean rejected=false;
        try{PcSystemSave.open(root.toFile(),sav.toFile(),game,chart,new int[]{3,0},true);}
        catch(java.io.IOException expected){rejected=true;}
        check(rejected,"legacy chart open rejects an external symbolic directory");
        Files.delete(link);
        PcSystemSave save=PcSystemSave.open(root.toFile(),sav.toFile(),game,chart,new int[]{3,0},true);
        Files.createSymbolicLink(link,outside.toAbsolutePath());
        rejected=false;
        try{save.write(withChart(chartFlags(10004,10000)),new int[0]);}
        catch(java.io.IOException expected){rejected=true;}
        check(rejected,"legacy chart write rejects a symbolic directory installed after open");
        check(Arrays.equals(Files.readAllBytes(outside.resolve("mita.dat")),seen)&&
            Arrays.equals(Files.readAllBytes(outside.resolve("tsuka.dat")),roads),"rejected chart operations leave external flags untouched");
    }

    private static void chartProgress(Path directory) throws Exception {
        Path root=Files.createDirectory(directory.resolve("chart-game"));
        Path init=Files.createDirectory(root.resolve("init"));
        Path spt=Files.createDirectory(root.resolve("spt"));
        Path sav=Files.createDirectory(root.resolve("sav"));
        Files.write(spt.resolve("var2.fxf"),new byte[0]);
        Files.write(init.resolve("game.xtx"),"SelectChartIsDefault 1\n".getBytes(StandardCharsets.UTF_8));
        Files.write(init.resolve("selectChart.xtx"),"chartBlockSize 5\n".getBytes(StandardCharsets.UTF_8));
        Files.write(sav.resolve("mita.dat"),new byte[]{0,1,0,0,0,0,0,0});
        Files.write(sav.resolve("tsuka.dat"),new byte[]{0,2,0,0,0});
        Path legacy=Files.createDirectory(sav.resolve("mushiex"));
        byte[] otherSeen=new byte[]{41},otherRoads=new byte[]{42};
        Files.write(legacy.resolve("mita.dat"),otherSeen);Files.write(legacy.resolve("tsuka.dat"),otherRoads);
        GameUiAssets.Config game=GameUiAssets.readConfig(root.toFile(),"game");
        GameUiAssets.Config chart=GameUiAssets.readConfig(root.toFile(),"selectChart");
        PcSystemSave save=PcSystemSave.open(root.toFile(),sav.toFile(),game,chart,new int[]{3,0});
        GameProgress.State state=save.restore(save.load(),blank());
        check(words(state.chart).getInt()==8&&words(state.chart).getInt(4)==5,"modern chart keeps its padded wire extent");
        check(state.chart[9]==1&&state.chart[17]==2,"PC chart files restored at their own offsets");
        state.chart[10]=3;state.chart[18]=4;
        save.write(state,new int[0]);
        check(Files.isRegularFile(sav.resolve("sysfile3.sav"))&&!Files.exists(sav.resolve("sysfile2.sav")),
            "the cached format selects the first system archive");
        check(Files.readAllBytes(sav.resolve("mita.dat"))[2]==3&&Files.readAllBytes(sav.resolve("tsuka.dat"))[2]==4,
            "chart progress was written in PC form");
        check(Arrays.equals(save.restore(save.load(),blank()).chart,state.chart),"modern chart progress survives reload");
        check(Arrays.equals(Files.readAllBytes(legacy.resolve("mita.dat")),otherSeen)&&
            Arrays.equals(Files.readAllBytes(legacy.resolve("tsuka.dat")),otherRoads),"modern chart never writes the legacy directory");
        byte[] seenBefore=Files.readAllBytes(sav.resolve("mita.dat")),roadsBefore=Files.readAllBytes(sav.resolve("tsuka.dat"));
        save.write(withChart(chartFlags(10004,10000)),new int[0]);
        check(Arrays.equals(Files.readAllBytes(sav.resolve("mita.dat")),seenBefore)&&
            Arrays.equals(Files.readAllBytes(sav.resolve("tsuka.dat")),roadsBefore),"modern writer rejects a legacy wire payload");
        Files.write(sav.resolve("mita.dat"),new byte[]{7});
        save.write(state,new int[0]);
        check(Files.size(sav.resolve("mita.dat"))==1,"invalid PC chart file was retained");
    }

    private static void legacyChartProgress(Path directory) throws Exception {
        Path root=Files.createDirectory(directory.resolve("legacy-chart-game"));
        Path init=Files.createDirectory(root.resolve("init")),sav=Files.createDirectory(root.resolve("sav"));
        Files.write(init.resolve("game.xtx"),"SelectChartClassDisable 1\n".getBytes(StandardCharsets.UTF_8));
        Files.write(init.resolve("chart.xtx"),"chartListNumber 9\nchartBlockSize 1000\n".getBytes(StandardCharsets.UTF_8));
        byte[] otherSeen=new byte[]{31,32},otherRoads=new byte[]{33,34};
        Files.write(sav.resolve("mita.dat"),otherSeen);Files.write(sav.resolve("tsuka.dat"),otherRoads);
        GameUiAssets.Config game=GameUiAssets.readConfig(root.toFile(),"game"),chart=GameUiAssets.readConfig(root.toFile(),"chart");
        PcSystemSave save=PcSystemSave.open(root.toFile(),sav.toFile(),game,chart,new int[]{3,0},true);
        byte[] flags=chartFlags(10004,10000);
        flags[8+1001]=5;flags[8+10004+9999]=6;
        byte[] tail={17,34,51,68};System.arraycopy(tail,0,flags,8+10000,tail.length);
        check(!Files.exists(sav.resolve("mushiex")),"a new legacy directory starts absent");
        save.write(withChart(flags),new int[0]);
        Path legacy=sav.resolve("mushiex"),seen=legacy.resolve("mita.dat"),roads=legacy.resolve("tsuka.dat");
        check(Files.size(seen)==10004&&Files.size(roads)==10000,"legacy chart keeps its fixed PC extents");
        check(Arrays.equals(Arrays.copyOfRange(Files.readAllBytes(seen),10000,10004),tail),"all four legacy tail bytes survive first write");
        check(Arrays.equals(Files.readAllBytes(sav.resolve("mita.dat")),otherSeen)&&
            Arrays.equals(Files.readAllBytes(sav.resolve("tsuka.dat")),otherRoads),"legacy chart never overwrites modern chart files");
        check(!Files.exists(sav.resolve("emberfall.chart")),"legacy progress does not create a second chart tree");
        PcSystemSave reopened=PcSystemSave.open(root.toFile(),sav.toFile(),game,chart,new int[]{3,0},true);
        byte[] stale=chartFlags(10004,10000);Arrays.fill(stale,8,stale.length,(byte)99);
        GameProgress.State restored=reopened.restore(reopened.load(),withChart(stale));
        check(Arrays.equals(restored.chart,flags),"PC legacy files replace stale Android flags without cropping the tail");
        flags[8+10000]=85;reopened.write(withChart(flags),new int[0]);
        check(Arrays.equals(reopened.restore(reopened.load(),blank()).chart,flags),"legacy tail edits survive another read/write cycle");
        PcSystemSave unrecognized=PcSystemSave.open(root.toFile(),sav.toFile(),game,chart,new int[]{3,0},false);
        check(unrecognized.restore(unrecognized.load(),blank()).chart==null,"chart filenames alone do not opt an unknown game into the legacy layout");
        byte[] beforeSeen=Files.readAllBytes(seen),beforeRoads=Files.readAllBytes(roads);
        for(byte[] unknown:new byte[][]{chartFlags(10000,10000),chartFlags(10008,9996)}){
            reopened.write(withChart(unknown),new int[0]);
            check(Arrays.equals(Files.readAllBytes(seen),beforeSeen)&&Arrays.equals(Files.readAllBytes(roads),beforeRoads),
                "legacy writer ignores modern or unknown wire extents");
        }
        Files.write(seen,Arrays.copyOf(beforeSeen,10000));
        reopened.write(withChart(flags),new int[0]);
        check(Files.size(seen)==10000,"a truncated legacy PC file is preserved, not expanded from Android state");
    }

    private static void chartWireProgress(Path directory) throws Exception {
        Path base=directory.resolve("emberfall.read");GameProgress store=new GameProgress(base.toFile());
        for(byte[] flags:new byte[][]{chartFlags(8,5),chartFlags(10000,10000),chartFlags(10004,10000)}){
            flags[flags.length-1]=73;if(flags.length==20012)Arrays.fill(flags,10008,10012,(byte)91);
            store.write(withChart(flags));
            check(Arrays.equals(store.read().chart,flags),"known chart wire layout round-trips unchanged");
        }
        Path extension=directory.resolve("emberfall.read.ext");
        byte[] beforeBase=Files.readAllBytes(base),beforeExtension=Files.readAllBytes(extension);
        for(byte[] unknown:new byte[][]{chartFlags(10008,9996),chartFlags(10004,9999),chartFlags(9,5),chartFlags(0,0)}){
            boolean rejected=false;try{store.write(withChart(unknown));}catch(java.io.IOException expected){rejected=true;}
            check(rejected,"unknown chart wire layout rejected on write");
            check(Arrays.equals(Files.readAllBytes(base),beforeBase)&&Arrays.equals(Files.readAllBytes(extension),beforeExtension),
                "rejected chart write preserves the committed progress generation");
        }
        byte[] corrupted=beforeExtension.clone();int payload=corrupted.length-20012;
        words(corrupted).putInt(payload,10008).putInt(payload+4,9996);
        Files.write(extension,corrupted);
        check(store.read().chart==null,"unknown chart shape is rejected when reading an otherwise valid extension");
        check(Arrays.equals(Files.readAllBytes(base),beforeBase),"invalid extension does not damage base progress");
    }

    private static byte[] chartFlags(int seen,int roads){
        return ByteBuffer.allocate(8+seen+roads).order(ByteOrder.LITTLE_ENDIAN).putInt(seen).putInt(roads).array();
    }
    private static GameProgress.State withChart(byte[] chart){
        GameProgress.State base=blank();
        return new GameProgress.State(base.read,base.variables,base.results,base.text,base.optional,chart,base.legacy);
    }

    private static GameProgress.State blank(){
        byte[][] text=new byte[102][0];
        return new GameProgress.State(new int[0],new int[1000],new int[2048],text,
            new int[NativeGame.OPTIONAL_PROGRESS_WORDS],null,Collections.emptyList());
    }
    private static ByteBuffer words(byte[] bytes){return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static void check(boolean condition,String subject){if(!condition)throw new AssertionError(subject);}
}
