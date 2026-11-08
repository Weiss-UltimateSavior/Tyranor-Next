package com.tyranor.runtime.systemnnn;

import java.util.*;

/** Read-only layout/parameter declarations from commonConfig; pages and character IDs stay one-based. */
final class OriginalSettingsModel {
    static final String[] VOLUMES={"messageSpeed","music","sound","voice","movie","soundvoice","windowPercent","autoSpeed","total","scriptse"};
    static final String[] KEYS={"textSpeed","musicVolume","soundVolume","voiceVolume","movieVolume","effectVoiceVolume","windowOpacity","autoSpeed","masterVolume","scriptSoundVolume"};
    final GameUiAssets.Config config;
    final int canvasWidth,canvasHeight;
    final int pages,pageMode,characterMin,characterMax;
    final boolean original;
    private final boolean resetRadioSource;
    final List<Slider> sliders=new ArrayList<>();
    final List<CharacterControl> characters=new ArrayList<>();
    final List<Choice> modes=new ArrayList<>(),checks=new ArrayList<>();
    final List<Extended> extended=new ArrayList<>();
    final List<Button> tabs=new ArrayList<>(),systemVoices=new ArrayList<>();

    static final class Button {
        final int x,y,onX,onY;
        final ButtonArt.Setup off,on;
        Button(int x,int y,int onX,int onY,ButtonArt.Setup off,ButtonArt.Setup on){
            this.x=x;this.y=y;this.onX=onX;this.onY=onY;this.off=off;this.on=on;
        }
        ButtonArt.Setup state(boolean value){return value?on:off;}
        int x(boolean value){return value?onX:x;}
        int y(boolean value){return value?onY:y;}
    }
    static final class Slider {
        final int index,page,x,y,width,height,steps,realMin,realMax;final String kind,key,image;final Button button;
        Slider(int i,int page,int x,int y,int w,int h,int steps,int min,int max,String image,Button button){
            index=i;kind=VOLUMES[i];key=KEYS[i];this.page=page;this.x=x;this.y=y;width=w;height=h;this.steps=steps;realMin=min;realMax=max;this.image=image;this.button=button;
        }
        int toPreference(int position){
            position=bound(position,0,steps);
            if(index==0)return Math.round(position*3f/steps);
            if(index==7)return Math.round(position*2f/steps);
            return Math.round(position*100f/steps);
        }
        int fromPreference(int value){return Math.round(bound(value,0,index==0?3:index==7?2:100)*steps/(index==0?3f:index==7?2f:100f));}
    }
    static final class CharacterControl {
        final int index,page;final int[] ids;final Button button;final boolean hasSlider;final int x,y,width,height;
        CharacterControl(int index,int page,int[] ids,Button button,boolean hasSlider,int x,int y,int w,int h){this.index=index;this.page=page;this.ids=ids;this.button=button;this.hasSlider=hasSlider;this.x=x;this.y=y;width=w;height=h;}
    }
    static final class Choice {
        final String key,label;final int page;final boolean defaultValue;final Button[] buttons;
        Choice(String key,String label,int page,boolean value,Button... buttons){this.key=key;this.label=label;this.page=page;defaultValue=value;this.buttons=buttons;}
    }
    static final class Extended {
        final int index,page;
        final boolean check;
        final Button[] buttons;
        Extended(int index,int page,boolean check,Button... buttons){
            this.index=index;this.page=page;this.check=check;this.buttons=buttons;
        }
        int parameter(){return (check?400:300)+index;}
        boolean locked(int mask){return index<8&&(mask&(1<<(index+(check?8:0))))!=0;}
    }
    OriginalSettingsModel(GameUiAssets.Config c){this(c,1280,720);}
    OriginalSettingsModel(GameUiAssets.Config c,int width,int height){this(c,null,width,height);}
    OriginalSettingsModel(GameUiAssets.Config c,GameUiAssets.Config game,int width,int height){
        this(c,game,width,height,false);
    }
    OriginalSettingsModel(GameUiAssets.Config c,GameUiAssets.Config game,int width,int height,boolean legacyVolumeDefaults){
        canvasWidth=width>0?width:1280;canvasHeight=height>0?height:720;
        config=c;pages=bound(c.number("modePageMax",1),1,16);pageMode=bound(c.number("pageMode",pages>1?1:0),0,2);
        resetRadioSource=game!=null&&game.number("notSrcCopyFlagEnable",0)!=0;
        int radioCount=bound(c.number("expRadioNumber",0),0,16),checkCount=bound(c.number("expCheckNumber",0),0,16);
        original=c.has("musicVolumePrintX")&&c.has("musicBarFileName")||radioCount>0||checkCount>0;
        int w=c.number("volumeSizeX",338),h=c.number("volumeSizeY",19);
        Button previousVolume=null;
        int count=bound(c.number("volumeNumber",6),0,VOLUMES.length);
        for(int i=0;i<count;i++){
            String kind=VOLUMES[i];if(c.number(kind+"VolumeExistFlag",legacyVolumeDefaults||i<6?1:0)==0)continue;
            w=bound(c.number("volumeSizeX"+(i+1),w),1,canvasWidth);h=bound(c.number("volumeSizeY"+(i+1),h),1,canvasHeight);
            int x=c.number(kind+"VolumePrintX",0),y=c.number(kind+"VolumePrintY",i*h);
            Button toggle=null;
            if(i>0&&i<6||i>=8){
                previousVolume=button(kind+"Button",previousVolume,false);
                int buttonX=c.number(kind+"ButtonPrintX",x-previousVolume.off.width);
                int buttonY=c.number(kind+"ButtonPrintY",y);
                toggle=new Button(buttonX,buttonY,buttonX,buttonY,previousVolume.off,previousVolume.on);
            }
            int min=c.number(kind+"RealVolumeMin",c.number("volumeMin",80)),max=c.number(kind+"RealVolumeMax",c.number("volumeMax",100));
            sliders.add(new Slider(i,page(kind+"VolumePrintPage",1),x,y,w,h,bound(c.number(kind+"VolumeDevide",i==0||i==7?4:100),1,10000),min,max,c.value(kind+"BarFileName","ta_opt_bar"),toggle));
        }
        characterMin=bound(c.number("charaVoiceVolumeMin",-15),-15,15);characterMax=bound(c.number("charaVoiceVolumeMax",15),characterMin,15);
        int charCount=c.number("voiceCutButtonFlag",1)==0?0:bound(c.number("voiceCutButtonNumber",0),0,72),nextId=1;
        boolean custom=c.number("voiceCutButtonCustomFlag",0)!=0,auto=c.number("voiceAutoLineSetFlag",0)!=0;
        int nx=c.number("voiceAutoLineNumberX",5),ny=c.number("voiceAutoLineNumberY",0);
        Button previousCharacter=null;
        for(int i=0;i<charCount;i++){
            int n=custom?bound(c.number("voiceOffNumberByButton"+(i+1),1),0,256):1;int[] ids=new int[n];
            for(int k=0;k<n;k++){ids[k]=custom?c.number("voiceOffButton"+(i+1)+"chara"+(k+1),nextId):i+1;nextId=ids[k]+1;}
            String prefix="voice"+(i+1)+"Button";
            Button b;
            if(auto&&(ny>0||nx>0)){
                int col=ny>0?i/ny:i%nx,row=ny>0?i%ny:i/nx;
                int x=c.number("voiceAutoLinePrintX",0)+col*c.number("voiceAutoLineNextX",100);
                int y=c.number("voiceAutoLinePrintY",0)+row*c.number("voiceAutoLineNextY",30);
                b=button(prefix,previousCharacter,false,x,y);
            }else b=button(prefix,previousCharacter,false);
            characters.add(new CharacterControl(i,page("voicePrintPage",1),ids,b,c.number("charaVoiceVolumeFlag",0)!=0,
                b.x+c.number("charaVolumePrintX",0),b.y+c.number("charaVolumePrintY",30),bound(c.number("charaVolumeSizeX",200),1,canvasWidth),bound(c.number("charaVolumeSizeY",20),1,canvasHeight)));
            previousCharacter=b;
        }
        String[] kinds={"screen","skip","auto"},modeKeys={"fullscreen","skipReadOnly","autoEnabled"},modeLabels={"显示模式","快进范围","自动播放"};
        for(int i=0;i<kinds.length;i++)if(c.number(kinds[i]+"ModeButtonExistFlag",1)!=0){String p=kinds[i];
            Button first=button(p+"1Button",null,true);
            modes.add(new Choice(modeKeys[i],modeLabels[i],page(p+"ModeButtonPrintPage",1),i<2,
                first,button(p+"2Button",first,true)));}
        for(String p:new String[]{"autoContinue","autoSkip","changeReadColor","changeSelectColor","continueVoice"})
            if(c.number(p+"CheckButtonExistFlag",0)!=0)checks.add(new Choice(p,p,page(p+"CheckButtonPrintPage",1),false,button(p+"Check",null,false)));
        for(int i=0;i<radioCount;i++){
            String prefix="exp"+(i+1)+"Radio";
            int pg=page(prefix+"PrintPage",1);
            if(pg<1||pg>pages)continue;
            Button[] options=new Button[bound(c.number(prefix,2),0,32)];
            Button priorOption=null;
            for(int k=0;k<options.length;k++){
                options[k]=priorOption=button(prefix+(k+1)+"Button",priorOption,true);
            }
            extended.add(new Extended(i,pg,false,options));
        }
        for(int i=0;i<checkCount;i++){
            String prefix="exp"+(i+1)+"Check";
            int pg=page(prefix+"PrintPage",1);
            if(pg>=1&&pg<=pages)extended.add(new Extended(i,pg,true,button(prefix,null,false)));
        }
        for(int i=0;i<pages;i++)tabs.add(button("tab"+(i+1)+"Button",i==0?null:tabs.get(i-1),true));
        int voiceThemes=0;
        if(game!=null){
            int voices=bound(game.number("useSystemVoice",0),0,72);
            if(voices>0)voiceThemes=voices+1;
        }else while(voiceThemes<73&&c.has("systemVoice"+(voiceThemes+1)+"Button1FileName"))voiceThemes++;
        for(int i=0;i<voiceThemes;i++)systemVoices.add(button("systemVoice"+(i+1)+"Button",i==0?null:systemVoices.get(i-1),true));
    }
    int page(String field,int fallback){return config.number(field,fallback);}
    String background(int page){
        if(!config.has("filenameBG")&&!config.has("filenameBG1"))return "ta_cnf_bg0"+page;
        String name=config.value("filenameBG","config_bg");
        for(int i=1;i<=page;i++)name=config.value("filenameBG"+i,name);
        return name;
    }
    private static int bound(int v,int lo,int hi){return Math.max(lo,Math.min(v,hi));}
    private Button button(String prefix,Button previous,boolean radio){
        int x=previous==null?0:previous.onX+previous.on.width;
        int y=previous==null?0:previous.onY+(radio?0:previous.on.height);
        return button(prefix,previous,radio,x,y);
    }
    private Button button(String prefix,Button previous,boolean radio,int defaultX,int defaultY){
        String first=prefix+"1",second=prefix+"2";
        // Both the radio group and the chained checks copy the previous button's second setup.
        ButtonArt.Setup off=new ButtonArt.Setup(config,first,previous==null?null:previous.on,radio&&resetRadioSource);
        ButtonArt.Setup on=new ButtonArt.Setup(config,second,off,false);
        int x=config.number(first+"PrintX",defaultX),y=config.number(first+"PrintY",defaultY);
        return new Button(x,y,config.number(second+"PrintX",x),config.number(second+"PrintY",y),off,on);
    }
}
