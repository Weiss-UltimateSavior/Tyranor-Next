package com.tyranor.runtime.systemnnn;

import java.util.*;

/** CGameCallBack's CSceneOptionButton declarations; all coordinates remain game pixels. */
final class GameStoryControlsModel {
    enum Action { MENU,QUICK_SAVE,QUICK_LOAD,CONFIG,BACKLOG,SAVE,LOAD,AUTO,SKIP,HIDE }
    static final String[] CONFIG_NAMES={"optionButton","quickSaveButton","quickLoadButton","quickConfigButton",
        "quickBacklogButton","freeSaveButton","freeLoadButton","freeAutoButton","freeSkipButton","freeWindowOffButton"};
    static final Action[] ACTIONS={Action.MENU,Action.QUICK_SAVE,Action.QUICK_LOAD,Action.CONFIG,Action.BACKLOG,
        Action.SAVE,Action.LOAD,Action.AUTO,Action.SKIP,Action.HIDE};
    static final String[] LABELS={"菜单","QSave","QLoad","环境设定","回看","保存进度","读取存档","Auto","Skip","隐藏"};
    final List<Button> buttons=new ArrayList<>();
    final boolean original;
    final boolean drawDuringTransitions;
    static final class Button {
        final Action action;final String label;
        final ButtonArt art;
        final int x,y,width,height;
        final int checkX,checkY,checkWidth,checkHeight,checkDeltaX,checkDeltaY,printMode,clickTime,sound,voice;
        Button(Action action,String label,String prefix,GameUiAssets.Config c){
            this.action=action;this.label=label;art=new ButtonArt(c,prefix,null);
            x=bound(c.number(prefix+"PrintX",0),-4096,4096);y=bound(c.number(prefix+"PrintY",0),-4096,4096);
            width=bound(c.number(prefix+"SizeX",16),1,4096);height=bound(c.number(prefix+"SizeY",16),1,4096);
            checkX=c.number(prefix+"CheckPicX",art.states[0].x);checkY=c.number(prefix+"CheckPicY",art.states[0].y);
            checkWidth=bound(c.number(prefix+"CheckSizeX",width),1,4096);checkHeight=bound(c.number(prefix+"CheckSizeY",height),1,4096);
            checkDeltaX=bound(c.number(prefix+"CheckDeltaX",0),-4096,4096);checkDeltaY=bound(c.number(prefix+"CheckDeltaY",0),-4096,4096);
            printMode=c.number(prefix+"PrintMode",1);clickTime=bound(c.number(prefix+"ClickTime",0),0,1000);
            sound=c.number(prefix+"ClickSound",0)-1;voice=c.number(prefix+"ClickVoice",0)-1;
        }
        int left(){return Math.min(x,x+checkDeltaX);}int top(){return Math.min(y,y+checkDeltaY);}
        int right(){return Math.max(x+width,x+checkDeltaX+checkWidth);}int bottom(){return Math.max(y+height,y+checkDeltaY+checkHeight);}
    }
    GameStoryControlsModel(GameUiAssets.Config game,GameUiAssets.Config print,Map<String,GameUiAssets.Config> configurations){
        drawDuringTransitions=print.number("drawOverrapOptionFlag",1)!=0;
        int save=game.number("quickSaveEnable",0),load=game.number("quickLoadEnable",save);
        for(int i=0;i<CONFIG_NAMES.length;i++){
            String name=CONFIG_NAMES[i];GameUiAssets.Config c=configurations.get(name);
            if(c==null||!c.has(name+"FileName")||game.number(name+"CreateFlag",i==0?1:0)==0)continue;
            if(i==1&&save==0)continue;
            if(i==2&&load==0)continue;
            buttons.add(new Button(ACTIONS[i],LABELS[i],name,c));
        }
        original=!buttons.isEmpty();
    }
    private static int bound(int v,int min,int max){return Math.max(min,Math.min(v,max));}
}
