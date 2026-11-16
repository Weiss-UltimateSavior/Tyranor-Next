package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Immutable settings snapshot shared by the UI, game worker and audio mixer. */
final class PlayerSettings {
    static final String MUSIC="musicVolume", SOUND="soundVolume", VOICE="voiceVolume", EFFECT_VOICE="effectVoiceVolume";
    static final String TEXT_SPEED="textSpeed", AUTO_SPEED="autoSpeed", SKIP_READ_ONLY="skipReadOnly";
    final int musicVolume,soundVolume,voiceVolume,effectVoiceVolume,textSpeed,autoSpeed,readingSize;
    final boolean skipReadOnly;
    final int windowOpacity,messageWindowIndex;
    final boolean changeReadColor;

    private PlayerSettings(int music,int sound,int voice,int effect,int text,int auto,int reading,boolean readOnly,
                           int opacity,int windowIndex,boolean readColor) {
        musicVolume=bound(music,100); soundVolume=bound(sound,100);
        voiceVolume=bound(voice,100); effectVoiceVolume=bound(effect,100);
        textSpeed=bound(text,3); autoSpeed=bound(auto,2); readingSize=reading; skipReadOnly=readOnly;
        windowOpacity=bound(opacity,100);messageWindowIndex=Math.max(0,windowIndex);changeReadColor=readColor;
    }
    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("systemnnn_player",Context.MODE_PRIVATE);
    }
    static SharedPreferences displayPreferences(Context context,File game) {
        String id=UUID.nameUUIDFromBytes(game.getPath().getBytes(StandardCharsets.UTF_8)).toString();
        return context.getSharedPreferences("systemnnn_display_"+id,Context.MODE_PRIVATE);
    }
    static PlayerSettings defaults() {
        return new PlayerSettings(80,80,80,80,1,1,SystemNnnHost.DEFAULT_READING_SIZE,true,100,0,false);
    }
    static PlayerSettings read(SharedPreferences preferences,int readingSize) {
        return new PlayerSettings(preferences.getInt(MUSIC,80),preferences.getInt(SOUND,80),
            preferences.getInt(VOICE,80),preferences.getInt(EFFECT_VOICE,80),
            preferences.getInt(TEXT_SPEED,1),preferences.getInt(AUTO_SPEED,1),readingSize,
            preferences.getBoolean(SKIP_READ_ONLY,true),preferences.getInt("windowOpacity",100),
            preferences.getInt("messageWindowIndex",0),preferences.getBoolean("changeReadColor",false));
    }
    private static int bound(int value,int maximum) { return Math.max(0,Math.min(value,maximum)); }
}
