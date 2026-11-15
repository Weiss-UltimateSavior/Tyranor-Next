package com.tyranor.runtime.systemnnn;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public final class NativePanelSaveTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private static final String SCRIPT="spt/start.spt";
    private static final byte[] SNAPSHOT={1,4,8,15,16,23,42};

    private byte[] encoded(GameSave journal,byte[] snapshot) throws IOException {
        int[] state={NativeGame.NATIVE_PANEL,123,5,800,600,16,0};
        GameSave.Metadata at=GameSave.metadata(0,1,SCRIPT,state,"","");
        byte[][] content={SCRIPT.getBytes(StandardCharsets.UTF_8),new byte[0]};
        File file=folder.newFile();
        try(FileOutputStream out=new FileOutputStream(file)){
            GameSave.writePayload(out,new GameSave.Loaded(journal,at,content,snapshot));
        }
        return Files.readAllBytes(file.toPath());
    }
    private static GameSave.Loaded decoded(byte[] bytes) throws IOException {
        return GameSave.readFrom(new ByteArrayInputStream(bytes),1,0,true);
    }
    @Test public void nativePanelKeepsItsSnapshotAndPointer() throws IOException {
        GameSave journal=new GameSave();journal.append(20,12,34,false,0);journal.recordNativeBoundary(45,67);
        GameSave.Loaded restored=decoded(encoded(journal,SNAPSHOT));
        assertEquals(NativeGame.NATIVE_PANEL,restored.metadata.wait);
        assertTrue(restored.save.requiresSnapshot());assertArrayEquals(SNAPSHOT,restored.nativeSnapshot);
        assertEquals(45,restored.save.lastInput().x);assertEquals(67,restored.save.lastInput().y);
    }
    @Test(expected=IOException.class) public void nativePanelRejectsAReplayOnlyJournal() throws IOException {
        GameSave journal=new GameSave();journal.append(20,12,34,false,0);
        decoded(encoded(journal,SNAPSHOT));
    }
    @Test(expected=IOException.class) public void nativePanelRequiresASnapshot() throws IOException {
        encoded(GameSave.nativeBaseline(),null);
    }
    @Test(expected=IOException.class) public void nativePanelRejectsADamagedSnapshot() throws IOException {
        byte[] bytes=encoded(GameSave.nativeBaseline(),SNAPSHOT);bytes[bytes.length-1]^=1;
        decoded(bytes);
    }
}
