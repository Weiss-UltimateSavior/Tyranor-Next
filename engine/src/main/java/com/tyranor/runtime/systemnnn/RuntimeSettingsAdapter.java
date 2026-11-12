package com.tyranor.runtime.systemnnn;

import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Android display/persistence conversion; the portable RuntimeContext remains the parameter owner. */
final class RuntimeSettingsAdapter {
    // commonSystemParamName.h; same order as commonConfig::m_volumeName.
    private static final int[] VOLUME_IDS={105,100,101,102,103,104,106,107,108,109};
    private static final int[] SWITCH_IDS={-1,200,201,202,203,204,-1,-1,208,209};
    private static final String[] BOOLEAN_KEYS={"skipReadOnly","fullscreen","autoEnabled","autoContinue","autoSkip","changeReadColor","changeSelectColor","continueVoice"};
    private static final int[] BOOLEAN_IDS={2,4,6,210,211,212,213,214};
    private static final int[] EMPTY=new int[0];
    private static final String RAW_PREFIX="runtime.raw.";

    private static String namespace(File game){
        try{return RAW_PREFIX+UUID.nameUUIDFromBytes(game.getCanonicalPath().getBytes(StandardCharsets.UTF_8))+".";}
        catch(IOException e){throw new IllegalArgumentException("Cannot identify game settings directory",e);}
    }
    private static boolean supported(int id){
        return id==2||id==4||id==6||id>=100&&id<=109||id>=200&&id<=204||id>=208&&id<=214||extended(id)||id>=500&&id<572;
    }
    private static boolean extended(int id){return id>=300&&id<316||id>=400&&id<416;}
    static String extendedKey(int id){return "extended."+id;}
    static String ownerKey(File game,int index){return namespace(game)+"owner."+index;}
    static boolean isPersistedKey(String key){return key!=null&&key.startsWith(RAW_PREFIX);}
    private static Integer integer(Map<String,?> values,String key){Object value=values.get(key);return value instanceof Integer?(Integer)value:null;}
    private static Boolean bool(Map<String,?> values,String key){Object value=values.get(key);return value instanceof Boolean?(Boolean)value:null;}
    private static int bound(int value,int min,int max){return Math.max(min,Math.min(value,max));}
    private static void pairs(int[] values){if(values==null||(values.length&1)!=0)throw new IllegalArgumentException("Expected runtime parameter id/value pairs");}
    private static int[] array(List<Integer> values){int[] result=new int[values.size()];for(int i=0;i<result.length;i++)result[i]=values.get(i);return result;}

    /** Only explicit, game-scoped raw overrides. No display values become factory defaults. */
    static int[] persistedOverrides(SharedPreferences preferences,File game){
        String prefix=namespace(game);Map<String,?> all=preferences.getAll();List<Integer> result=new ArrayList<>();
        for(int id=0;id<572;id++)if(supported(id)){
            Integer value=integer(all,prefix+id);if(value!=null){result.add(id);result.add(value);}
        }
        // Migration TODO: old global eight-setting values used different units
        // and defaults. Import requires an explicit migration policy, not a guess.
        return array(result);
    }

    /** Persist accepted raw changes/snapshots supplied by the owner, never a converted UI cache. */
    static void persistRawOverrides(SharedPreferences preferences,File game,int[] actualPairs){
        pairs(actualPairs);String prefix=namespace(game);SharedPreferences.Editor edit=preferences.edit();
        for(int i=0;i<actualPairs.length;i+=2)if(supported(actualPairs[i]))edit.putInt(prefix+actualPairs[i],actualPairs[i+1]);
        edit.apply();
    }

    private static OriginalSettingsModel.Slider slider(OriginalSettingsModel model,int index){
        for(OriginalSettingsModel.Slider s:model.sliders)if(s.index==index)return s;
        // A mobile fallback control can exist without the original layout.
        // Retain commonConfig's declarations/defaults rather than mobile units.
        String kind=OriginalSettingsModel.VOLUMES[index];
        GameUiAssets.Config config=model.config;
        return new OriginalSettingsModel.Slider(index,0,0,0,1,1,
            bound(config.number(kind+"VolumeDevide",index==0||index==7?4:100),1,10000),
            config.number(kind+"RealVolumeMin",config.number("volumeMin",80)),
            config.number(kind+"RealVolumeMax",config.number("volumeMax",100)),"",null);
    }

    // commonConfig::Calcu: total is unadjusted, window is reversed, speed is
    // the exact gaze; only audio indices1..5 and9 receive real-volume mapping.
    private static int raw(OriginalSettingsModel.Slider s,int position){
        if(s.index==0||s.index==7)return position;
        position=bound(position,0,s.steps);
        if(s.index==6)return 100-position;
        if(s.index==8)return position;
        long value=s.realMin+((long)s.realMax-s.realMin)*position/s.steps;
        return (int)Math.max(0,Math.min(100,value));
    }
    // commonConfig::Init: clamps only the displayed audio position. Keeping a
    // raw auto value50 in persistedOverrides must never depend on this view.
    private static int position(OriginalSettingsModel.Slider s,int raw){
        if(s.index==0||s.index==7||s.index==8)return raw;
        if(s.index==6){long value=100L-raw;return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,value));}
        long value=raw;
        if(value<s.realMin)value=s.realMin;if(value>s.realMax)value=s.realMax;
        long denominator=Math.max(1L,(long)s.realMax-s.realMin);
        return (int)Math.max(0,Math.min(s.steps,(value-s.realMin)*s.steps/denominator));
    }

    static int[] changedParameter(SharedPreferences preferences,GameUiAssets assets,String key){
        if(key==null)return EMPTY;
        Map<String,?> values=preferences.getAll();
        if(key.startsWith("extended.")){
            try{
                int id=Integer.parseInt(key.substring("extended.".length()));Integer value=integer(values,key);
                return extended(id)&&value!=null?new int[]{id,value}:EMPTY;
            }catch(NumberFormatException e){return EMPTY;}
        }
        OriginalSettingsModel model=assets.settingsModel(1280,720);
        for(int i=0;i<OriginalSettingsModel.KEYS.length;i++){
            String display=OriginalSettingsModel.KEYS[i],exact="originalSlider."+OriginalSettingsModel.VOLUMES[i];
            if((i==0||i==7)&&key.equals(exact)){
                Integer value=integer(values,exact);
                if(value!=null)return new int[]{VOLUME_IDS[i],value};
                // Removing the exact key is an explicit switch to the mobile fallback.
                Integer mobile=integer(values,display);
                if(mobile==null)return EMPTY;
                OriginalSettingsModel.Slider s=slider(model,i);
                return new int[]{VOLUME_IDS[i],raw(s,s.fromPreference(mobile))};
            }
            if(key.equals(display)){
                if((i==0||i==7)&&integer(values,exact)!=null)return EMPTY;
                Integer value=integer(values,display);if(value==null)return EMPTY;
                OriginalSettingsModel.Slider s=slider(model,i);
                return new int[]{VOLUME_IDS[i],raw(s,s.fromPreference(value))};
            }
            if(SWITCH_IDS[i]>=0&&key.equals("enabled."+display)){
                Boolean value=bool(values,key);return value==null?EMPTY:new int[]{SWITCH_IDS[i],value?1:0};
            }
        }
        for(int i=0;i<BOOLEAN_KEYS.length;i++)if(key.equals(BOOLEAN_KEYS[i])){
            Boolean value=bool(values,key);if(value==null)return EMPTY;
            return new int[]{BOOLEAN_IDS[i],i==0?(value?0:1):(value?1:0)};
        }
        if(key.startsWith("character.")&&key.endsWith(".steps")){
            Integer value=integer(values,key);if(value==null)return EMPTY;
            for(OriginalSettingsModel.CharacterControl group:model.characters)for(int id:group.ids)
                if(key.equals("character."+id+".steps"))return new int[]{500+group.index,value};
        }
        // Character enabled/theme/window have dedicated source owners. Font is
        // Android presentation only. No numeric identity is fabricated for them.
        return EMPTY;
    }

    /** One display-only Editor transaction. Caller suppresses feedback while applying it. */
    static void syncDisplay(SharedPreferences preferences,OriginalSettingsModel model,int[] corePairs){
        pairs(corePairs);SharedPreferences.Editor edit=preferences.edit();
        for(int at=0;at<corePairs.length;at+=2){
            int id=corePairs[at],value=corePairs[at+1];if(!supported(id))continue;
            if(extended(id))edit.putInt(extendedKey(id),value);
            for(int i=0;i<VOLUME_IDS.length;i++){
                if(id==VOLUME_IDS[i]){
                    OriginalSettingsModel.Slider s=slider(model,i);
                    edit.putInt(OriginalSettingsModel.KEYS[i],s.toPreference(position(s,value)));
                    if(i==0||i==7)edit.putInt("originalSlider."+s.kind,value);
                }
                if(SWITCH_IDS[i]>=0&&id==SWITCH_IDS[i])edit.putBoolean("enabled."+OriginalSettingsModel.KEYS[i],value!=0);
            }
            for(int i=0;i<BOOLEAN_IDS.length;i++)if(id==BOOLEAN_IDS[i])edit.putBoolean(BOOLEAN_KEYS[i],i==0?value==0:value!=0);
            if(id>=500)for(OriginalSettingsModel.CharacterControl group:model.characters)if(id==500+group.index)
                for(int character:group.ids)edit.putInt("character."+character+".steps",value);
        }
        edit.apply();
    }
}
