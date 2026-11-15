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
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class OriginalSettingsModelTest {
    @Test public void windowOpacityAlwaysUsesTheFullPercentScale() {
        OriginalSettingsModel.Slider slider = new OriginalSettingsModel.Slider(
            6, 1, 0, 0, 120, 20, 10, 0, 100, "", null
        );
        assertEquals(100, slider.steps);
        assertEquals(100, slider.toPreference(slider.steps));
        assertEquals(63, slider.fromPreference(63));
    }
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

    @Test public void finishingOlderWritesKeepsTheRepeatedSettingsChange(){
        File game=folder.getRoot(),other=new File(game,"other");
        SharedPreferences preferences=values(Map.of());
        RuntimeSettingsAdapter.persistRawOverrides(preferences,game,new int[]{105,20});
        RuntimeSettingsAdapter.persistOwner(preferences,game,1,7);
        RuntimeSettingsAdapter.AudioOverrides first=RuntimeSettingsAdapter.audioOverrides(preferences,game);
        RuntimeSettingsAdapter.persistRawOverrides(preferences,game,new int[]{105,30});
        RuntimeSettingsAdapter.AudioOverrides second=RuntimeSettingsAdapter.audioOverrides(preferences,game);
        RuntimeSettingsAdapter.persistRawOverrides(preferences,game,new int[]{105,20});
        RuntimeSettingsAdapter.AudioOverrides latest=RuntimeSettingsAdapter.audioOverrides(preferences,game);

        RuntimeSettingsAdapter.clearSavedAudioOverrides(preferences,first,first.raw,first.owners);
        RuntimeSettingsAdapter.clearSavedAudioOverrides(preferences,second,second.raw,second.owners);
        assertArrayEquals(new int[]{105,20},RuntimeSettingsAdapter.persistedOverrides(preferences,game));
        assertArrayEquals(new int[]{1,7},RuntimeSettingsAdapter.persistedOwners(preferences,game));

        RuntimeSettingsAdapter.persistOwner(preferences,game,1,7);
        RuntimeSettingsAdapter.clearSavedAudioOverrides(preferences,latest,latest.raw,latest.owners);
        assertArrayEquals(new int[]{1,7},RuntimeSettingsAdapter.persistedOwners(preferences,game));
        assertArrayEquals(new int[]{105,20},RuntimeSettingsAdapter.persistedOverrides(preferences,game));

        RuntimeSettingsAdapter.AudioOverrides saved=RuntimeSettingsAdapter.audioOverrides(preferences,game);
        RuntimeSettingsAdapter.persistRawOverrides(preferences,other,new int[]{105,60});
        RuntimeSettingsAdapter.clearSavedAudioOverrides(preferences,saved,saved.raw,saved.owners);
        assertArrayEquals(new int[0],RuntimeSettingsAdapter.persistedOverrides(preferences,game));
        assertArrayEquals(new int[0],RuntimeSettingsAdapter.persistedOwners(preferences,game));
        assertArrayEquals(new int[]{105,60},RuntimeSettingsAdapter.persistedOverrides(preferences,other));
    }

    private static SharedPreferences values(Map<String,?> entries){
        Map<String,Object> store=new HashMap<>(entries);
        return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class},(prefs,method,args)->{
                switch(method.getName()){
                case "getAll":return new HashMap<>(store);
                case "getLong":return store.getOrDefault((String)args[0],args[1]);
                case "edit":return edit(store);
                default:throw new AssertionError(method);
                }
            });
    }

    private static SharedPreferences.Editor edit(Map<String,Object> store){
        Map<String,Object> updates=new HashMap<>();
        return (SharedPreferences.Editor)Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.Editor.class},(editor,method,args)->{
                switch(method.getName()){
                case "putInt":case "putLong":updates.put((String)args[0],args[1]);return editor;
                case "remove":updates.put((String)args[0],null);return editor;
                case "apply":
                    updates.forEach((key,value)->{if(value==null)store.remove(key);else store.put(key,value);});
                    return null;
                default:throw new AssertionError(method);
                }
            });
    }

    @Test public void pcLoadKeepsItsAutoDecisionInsteadOfReapplyingPreferences(){
        File game=folder.getRoot();
        String prefix=RuntimeSettingsAdapter.ownerKey(game,0).replace("owner.0","");
        for(int auto:new int[]{0,1}){
            SharedPreferences preferences=values(Map.of(prefix+6,auto,prefix+105,72));
            assertArrayEquals(new int[]{6,auto,105,72},RuntimeSettingsAdapter.persistedOverrides(preferences,game));
            assertArrayEquals(new int[]{105,72},RuntimeSettingsAdapter.persistedOverrides(preferences,game,false,false));
        }
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
