package com.tyranor.runtime.systemnnn;

/** The four original CCommonButton atlas states, independent of Android views. */
final class ButtonArt {
    enum SoundEvent {
        CLICK("Click","Click"), CANNOT_CLICK("Bad","Cannot"), ENTER("Enter","Enter"), EXIT("Exit","Exit");

        final String field, changeField;
        SoundEvent(String field,String changeField){this.field=field;this.changeField=changeField;}
    }

    static final class State {
        final int x,y,type,frames,speed;
        State(int x,int y,int type,int frames,int speed) {
            this.x=x;this.y=y;this.type=type;this.frames=frames;this.speed=speed;
        }
    }

    /** Resolved CSuperButtonSetup for one state of an original settings control. */
    static final class Setup {
        final ButtonArt art;
        final int width,height,clickTime,sound,voice,volumeType;
        final int enterWait,exitWait;
        private final int[] sounds=new int[SoundEvent.values().length],voices=new int[sounds.length];
        final int hitX,hitY,hitPicX,hitPicY,hitWidth,hitHeight;
        final boolean pixelHit;

        Setup(GameUiAssets.Config config,String key,Setup previous,boolean resetSource) {
            width=dimension(config.number(key+"SizeX",previous==null?16:previous.width));
            height=dimension(config.number(key+"SizeY",previous==null?16:previous.height));
            art=new ButtonArt(config,key,width,previous==null?null:previous.art,resetSource);
            clickTime=Math.max(0,Math.min(1000,config.number(key+"ClickTime",previous==null?8:previous.clickTime)));
            for(SoundEvent event:SoundEvent.values()){
                int id=event.ordinal();
                sounds[id]=cue(config.number(key+event.field+"Sound",previous==null?0:previous.sounds[id]+1));
                voices[id]=cue(config.number(key+event.field+"Voice",previous==null?0:previous.voices[id]+1));
            }
            sound=sound(SoundEvent.CLICK);
            voice=voice(SoundEvent.CLICK);
            volumeType=config.number(key+"VolumeType",previous==null?0:previous.volumeType);
            enterWait=config.number(key+"EnterWait",previous==null?0:previous.enterWait);
            // CSuperButtonSetup copies EnterWait into both fields before applying overrides.
            exitWait=config.number(key+"ExitWait",previous==null?0:previous.enterWait);
            hitX=config.number(key+"CheckDeltaX",previous==null?0:previous.hitX);
            hitY=config.number(key+"CheckDeltaY",previous==null?0:previous.hitY);
            hitPicX=config.number(key+"CheckPicX",previous==null?0:previous.hitPicX);
            hitPicY=config.number(key+"CheckPicY",previous==null?0:previous.hitPicY);
            // CSuperButtonSetup defaults the hit size to the new state's size after copying.
            hitWidth=config.number(key+"CheckSizeX",width);
            hitHeight=config.number(key+"CheckSizeY",height);
            pixelHit=config.number(key+"PrintMode",previous!=null&&!previous.pixelHit?0:2)!=0;
        }

        int sound(SoundEvent event){return sounds[event.ordinal()];}
        int voice(SoundEvent event){return voices[event.ordinal()];}
        private static int cue(int number){return number>0?number-1:-1;}
        private static int dimension(int size){return Math.max(1,Math.min(4096,size));}
    }

    final String image;
    final boolean horizontal;
    final State[] states=new State[4];

    ButtonArt(GameUiAssets.Config config,String key,String fallback) {
        image=config.value(key+"FileName",fallback==null?"":config.value(fallback+"FileName",""));
        horizontal=number(config,key,fallback,"TateYoko",0)!=0;
        int normalX=number(config,key,fallback,"PicSrcX1",0);
        int normalY=number(config,key,fallback,"PicSrcY1",0);
        for(int i=0;i<states.length;i++) {
            int state=i+1;
            states[i]=new State(number(config,key,fallback,"PicSrcX"+state,normalX),
                number(config,key,fallback,"PicSrcY"+state,state==4?-1:normalY),
                number(config,key,fallback,"AnimeType"+state,1),
                number(config,key,fallback,"AnimePattern"+state,1),
                number(config,key,fallback,"AnimeSpeed"+state,1));
        }
    }

    private ButtonArt(GameUiAssets.Config config,String key,int width,ButtonArt previous,boolean resetSource) {
        image=config.value(key+"FileName",previous==null?"":previous.image);
        horizontal=config.number(key+"TateYoko",previous!=null&&previous.horizontal?1:0)!=0;
        State defaults=new State(0,0,1,1,1);
        for(int i=0;i<states.length;i++) {
            int state=i+1;
            State base=previous==null?defaults:previous.states[i];
            int x=previous==null||resetSource?width*i:base.x;
            int y=previous==null||resetSource?0:base.y;
            states[i]=new State(config.number(key+"PicSrcX"+state,x),config.number(key+"PicSrcY"+state,y),
                config.number(key+"AnimeType"+state,base.type),config.number(key+"AnimePattern"+state,base.frames),
                config.number(key+"AnimeSpeed"+state,base.speed));
            defaults=states[i];
        }
    }

    static int number(GameUiAssets.Config config,String key,String fallback,String suffix,int value) {
        return config.number(key+suffix,fallback==null?value:config.number(fallback+suffix,value));
    }
}
