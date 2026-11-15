package com.tyranor.runtime.systemnnn;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class GalleryCatalogTest {
    @Test public void keepsTheJointUnlockMarkerSeparateFromTheImageName() {
        GalleryCatalog.CgVariant variant = GalleryCatalog.cgVariant("*@!ev005", 9);
        assertEquals("ev005", variant.name);
        assertTrue(variant.jointUnlock);
    }
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void ignoresFieldsAfterTheLastCompleteSong() throws Exception {
        File root=folder.getRoot();
        File list=new File(folder.newFolder("nya"),"listenbgmlist.xtx");
        Files.write(list.toPath(),("first,track1,1,0,@,*,@,@\n"
            +"last,track2,2,59,@,*,@,@,unused\n").getBytes(StandardCharsets.US_ASCII));

        GameTextDecoder decoder=GameTextDecoder.load(root);
        GalleryCatalog catalog=new GalleryCatalog(root,decoder,0,new GalleryCatalog.MovieList(root,decoder));
        assertEquals(2,catalog.songCount());
        assertEquals("track2",catalog.song(1).name);
        assertEquals(4,catalog.song(1).titleBytes);
    }

    @Test public void usesOriginalByteLengthForTitleAlignment() {
        assertEquals(30,GalleryCatalog.musicNameOffset(1,12,14,false,16));
        assertEquals(22,GalleryCatalog.musicNameOffset(2,12,15,true,9));
        assertEquals(0,GalleryCatalog.musicNameOffset(0,12,14,false,16));
    }
}
