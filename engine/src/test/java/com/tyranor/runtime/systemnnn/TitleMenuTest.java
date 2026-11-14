package com.tyranor.runtime.systemnnn;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class TitleMenuTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void loadMaskKeepsAnySaveSeparateFromLastSelection() throws Exception {
        File root=folder.getRoot();
        GameUiAssets.Config defaults=GameUiAssets.readConfig(root,"title");
        assertTrue(TitleMenu.saveActionEnabled(defaults,TitleMenu.Action.LOAD,false,false));
        Path init=folder.newFolder("init").toPath();
        Files.write(init.resolve("title.xtx"),"noSaveDataToMaskMenu 0\n".getBytes(StandardCharsets.UTF_8));
        GameUiAssets.Config unmasked=GameUiAssets.readConfig(root,"title");
        assertTrue(TitleMenu.saveActionEnabled(unmasked,TitleMenu.Action.LOAD,false,false));
        Path init2=folder.newFolder("init2").toPath();
        byte[] encrypted="noSaveDataToMaskMenu 1\n".getBytes(StandardCharsets.UTF_8);
        for(int i=0;i<encrypted.length;i++)encrypted[i]^=(byte)255;
        Files.write(init2.resolve("title.fxf"),encrypted);
        GameUiAssets.Config masked=GameUiAssets.readConfig(root,"title");
        assertFalse(TitleMenu.saveActionEnabled(masked,TitleMenu.Action.LOAD,false,false));
        assertFalse(TitleMenu.saveActionEnabled(masked,TitleMenu.Action.LOAD,false,true));
        assertTrue(TitleMenu.saveActionEnabled(masked,TitleMenu.Action.LOAD,true,false));
        for(TitleMenu.Action action:new TitleMenu.Action[]{TitleMenu.Action.CONTINUE,TitleMenu.Action.LAST_LOAD}){
            assertFalse(TitleMenu.saveActionEnabled(masked,action,true,false));
            assertTrue(TitleMenu.saveActionEnabled(masked,action,false,true));
        }
        assertTrue(TitleMenu.saveActionEnabled(masked,TitleMenu.Action.START,false,false));
    }

    @Test public void loadAvailabilityUsesItsOwnPhysicalPcSlots() throws Exception {
        File game=folder.getRoot();
        Path init=folder.newFolder("init").toPath();
        Files.write(init.resolve("load.xtx"),"blockNumberX 2\nblockNumberY 5\npage 10\n".getBytes(StandardCharsets.UTF_8));
        Files.write(init.resolve("save.xtx"),"blockNumberX 2\nblockNumberY 5\npage 12\n".getBytes(StandardCharsets.UTF_8));
        Files.write(init.resolve("dataFile.xtx"),"quickSlotNumber 1\n".getBytes(StandardCharsets.UTF_8));
        GameSavePaths paths=GameSavePaths.forGame(game);
        Path saves=Files.createDirectory(paths.directory().toPath());
        GameTextDecoder decoder=GameTextDecoder.load(game);
        SaveSlots slots=new SaveSlots(paths);
        slots.preload(game,decoder);
        assertFalse(slots.hasAnyLoadData());assertNull(slots.lastSelectedSlot());
        Files.write(saves.resolve("tdata7.sav"),new byte[]{1});
        Files.write(paths.file(".resume").toPath(),new byte[]{1});
        Files.write(paths.file(".quick.sav").toPath(),new byte[]{1});
        Files.write(saves.resolve("sysfile3.sav"),new byte[]{1});
        slots.preload(game,decoder);
        assertFalse("Other namespaces and global data do not enable retail LOAD",slots.hasAnyLoadData());
        Files.delete(saves.resolve("tdata7.sav"));
        PcStorySave.Preview preview=new PcStorySave.Preview(1,0,"PC slot",null);
        slots.savedPc(100,preview);slots.savedPc(SaveSlots.MAX_MANUAL_SLOTS-1,preview);
        assertFalse("Save-only pages and retained high slots are outside the LOAD grid",slots.hasAnyLoadData());
        slots.savedPc(99,preview);
        assertTrue(slots.hasAnyLoadData());assertNull(slots.lastSelectedSlot());
        slots.preload(game,decoder);
        slots.savedPc(slots.quickNumber(),preview);
        assertEquals(0,slots.quickNumber());assertTrue(slots.hasAnyLoadData());assertNull(slots.lastSelectedSlot());
        Files.write(saves.resolve("data0.sav"),new byte[]{1});
        // Reject the pending entry before graphics/JNI; existence still enables the original load menu.
        Files.createDirectory(saves.resolve("data0.sav.new"));
        slots.preload(game,decoder);
        assertTrue(slots.entry(0).corrupt);assertTrue(slots.hasAnyLoadData());assertNull(slots.lastSelectedSlot());
        Files.move(saves.resolve("data0.sav"),saves.resolve("data0.sav.bak"));
        slots.preload(game,decoder);
        assertTrue(slots.entry(0).exists);assertTrue(slots.hasAnyLoadData());
        Files.write(init.resolve("game.xtx"),"taikenflag 1\n".getBytes(StandardCharsets.UTF_8));
        slots.preload(game,decoder);
        assertFalse("Trial LOAD ignores retail slots and backups",slots.hasAnyLoadData());
        Files.write(saves.resolve("tdata0.sav"),new byte[]{1});
        Files.createDirectory(saves.resolve("tdata0.sav.new"));
        slots.preload(game,decoder);
        assertTrue(slots.hasAnyLoadData());assertEquals("tdata0.sav",slots.pcFile(0).getName());
    }
}
