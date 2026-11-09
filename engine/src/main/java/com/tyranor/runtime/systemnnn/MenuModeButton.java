package com.tyranor.runtime.systemnnn;

import com.core.engine.R;

import android.content.Context;
import com.core.engine.EngineUiText;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Extra CMenuButtonSetup entries whose configured action opens an original mode. */
final class MenuModeButton {
    final int index,mode,x,y;
    final String label;
    final boolean enabled;
    final ButtonArt.Setup setup;

    private MenuModeButton(int index,int mode,int x,int y,String label,boolean enabled,ButtonArt.Setup setup){
        this.index=index;this.mode=mode;this.x=x;this.y=y;this.label=label;this.enabled=enabled;this.setup=setup;
    }

    static List<MenuModeButton> read(GameUiAssets.Config config,long handle,int basic,int builtIn){
        return read(config,handle,basic,builtIn,false);
    }

    static List<MenuModeButton> readTitle(GameUiAssets.Config config,long handle,int basic,int builtIn){
        return read(config,handle,basic,builtIn,true);
    }

    private static List<MenuModeButton> read(GameUiAssets.Config config,long handle,int basic,int builtIn,boolean includeBasic){
        int modes=DialogueLayout.clamp(config.number("extModeNumber",0),0,32);
        int extra=modes==0?0:DialogueLayout.clamp(config.number("extButtonNumber",1),0,64);
        String variable=config.value("extModeCheckVarName","");
        int mode=modes==0||variable.isEmpty()?0:NativeGame.menuVariable(handle,variable.getBytes(StandardCharsets.ISO_8859_1));
        if(mode<0||mode>modes)mode=0;
        List<MenuModeButton> buttons=new ArrayList<>();
        ButtonArt.Setup previous=null;
        int x=0,y=0;
        int nextX=config.number("menuNextX",0),nextY=config.number("menuNextY",config.number("menu1Button1SizeY",16));
        for(int index=0;index<basic+extra;index++){
            String tag="menu1Button"+(index+1);
            if(index>0){x+=nextX;y+=nextY;}
            x=config.number(tag+"PrintX",x);y=config.number(tag+"PrintY",y);
            previous=new ButtonArt.Setup(config,tag,previous,false);
            int target=index<basic?config.number("basicButtonSpecialMode"+(index+1),-1):
                config.number("extButtonSpecialMode"+(index-basic+1),-1);
            if(index<builtIn){
                if(!includeBasic||index>=basic)continue;
            }else if(target<=0||target>49)continue;
            boolean exists=config.number(tag+"ExistFlag",index<basic?1:0)!=0;
            boolean enabled=config.number(tag+"EnableFlag",exists?1:0)!=0;
            ButtonArt.Setup resolved=previous;
            int atX=x,atY=y;
            for(int variant=1;variant<=mode;variant++){
                tag="menu"+(variant+1)+"Button"+(index+1);
                if(variant==1&&index>=basic)exists=true;
                exists=config.number(tag+"ExistFlag",exists?1:0)!=0;
                enabled=config.number(tag+"EnableFlag",exists&&(index>=basic||enabled)?1:0)!=0;
                resolved=new ButtonArt.Setup(config,tag,resolved,false);
                atX=config.number(tag+"PrintX",atX);atY=config.number(tag+"PrintY",atY);
            }
            if(exists)buttons.add(new MenuModeButton(index,target,atX,atY,config.text(tag+"Name",""),enabled,resolved));
        }
        return buttons;
    }

    static String fallbackLabel(Context context,int mode){
        int resource;
        switch(mode){
        case 24:resource=R.string.engine_systemnnn_ctl_mode_encyclopedia;break;
        case 29:resource=R.string.engine_systemnnn_ctl_name_input;break;
        case 33:resource=R.string.engine_systemnnn_ctl_mode_result;break;
        case 34:resource=R.string.engine_systemnnn_ctl_mode_chart;break;
        case 36:case 39:resource=R.string.engine_systemnnn_ctl_mode_hint;break;
        case 38:resource=R.string.engine_systemnnn_ctl_mode_status;break;
        case 42:resource=R.string.engine_systemnnn_ctl_mode_achievement;break;
        case 43:resource=R.string.engine_systemnnn_ctl_mode_terms;break;
        case 44:resource=R.string.engine_systemnnn_ctl_mode_voice;break;
        case 47:resource=R.string.engine_systemnnn_ctl_mode_items;break;
        case 48:resource=R.string.engine_systemnnn_ctl_mode_shop;break;
        case 49:resource=R.string.engine_systemnnn_ctl_mode_party;break;
        default:return EngineUiText.localizedContext(context).getString(R.string.engine_systemnnn_ctl_mode_extension,mode);
        }
        return EngineUiText.get(context,resource);
    }
}
