package com.tyranor.runtime.systemnnn;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OriginalSettingsModelTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void characterArtUsesConfiguredVoiceGroupAndStoryVariant() throws Exception {
        Path init=folder.newFolder("init2").toPath();
        String config="voiceCutButtonNumber 1\n"+
            "voiceCutButtonCustomFlag 1\n"+
            "voiceCutButtonCheckAppearFlag 1\n"+
            "voiceOffNumberByButton1 2\n"+
            "voiceOffButton1chara1 8\n"+
            "voiceOffButton1chara2 9\n"+
            "voiceNameChangeFlag 1\n"+
            "voiceNameChangeVar1 portraitMode\n"+
            "voice1Button1FileName PortraitOff.DWQ\n"+
            "voice1Button2FileName PortraitOn\n";
        byte[] bytes=config.getBytes(StandardCharsets.US_ASCII);
        for(int i=0;i<bytes.length;i++)bytes[i]^=(byte)255;
        Files.write(init.resolve("config.fxf"),bytes);
        OriginalSettingsModel model=new OriginalSettingsModel(GameUiAssets.readConfig(folder.getRoot(),"config"));
        OriginalSettingsModel.CharacterControl control=model.characters.get(0);

        assertFalse(control.visible(id->id==1));
        assertTrue(control.visible(id->id==8));
        assertTrue(control.visible(id->id==9));
        assertEquals("portraitMode",control.imageVariable);
        assertEquals("sys/portraitoff_2",control.variantImage(false,2));
        assertEquals("sys/portraiton_2",control.variantImage(true,2));
        assertEquals("",control.variantImage(true,0));
        assertNull(control.soundChange);
    }
}
