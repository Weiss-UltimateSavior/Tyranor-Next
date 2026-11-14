package com.tyranor.runtime.systemnnn;

import android.content.SharedPreferences;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OriginalSettingsModelTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test public void selectedRadioIgnoresRepeatedClick() {
        int[] changes={0};
        Runnable select=()->changes[0]++;
        assertNull(GameSettingsPanel.radioAction(true,select));
        GameSettingsPanel.radioAction(false,select).run();
        assertEquals(1,changes[0]);
    }

    @Test public void launchModeAndInGameWindowChoiceShareThePcParameter() {
        for(boolean fullscreen:new boolean[]{false,true}){
            SharedPreferences preferences=values(Map.of(RuntimeSettingsAdapter.FULLSCREEN,fullscreen));
            int[] expected={4,fullscreen?1:0};
            assertArrayEquals(expected,RuntimeSettingsAdapter.screenMode(fullscreen));
            assertArrayEquals(expected,RuntimeSettingsAdapter.changedParameter(preferences,null,RuntimeSettingsAdapter.FULLSCREEN));
        }

        File game=folder.getRoot();
        SharedPreferences pending=values(Map.of(
            RuntimeSettingsAdapter.ownerKey(game,0).replace("owner.0","4"),0,
            RuntimeSettingsAdapter.ownerKey(game,0).replace("owner.0","105"),2));
        assertArrayEquals(new int[]{4,0,105,2},RuntimeSettingsAdapter.persistedOverrides(pending,game));
        assertArrayEquals(new int[]{105,2},RuntimeSettingsAdapter.persistedOverrides(pending,game,false));
    }

    private static SharedPreferences values(Map<String,?> entries){
        return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class},(prefs,method,args)->entries);
    }

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
