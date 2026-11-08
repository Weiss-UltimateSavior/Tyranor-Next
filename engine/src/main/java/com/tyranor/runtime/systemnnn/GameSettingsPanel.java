package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;

/** Configuration pages retain the selected game's artwork, coordinates and parameter groups. */
final class GameSettingsPanel extends GameUiPanel {
    private final SharedPreferences preferences;
    private final GameRunner runner;
    private final Runnable close;
    private final boolean previewMutedVoice;
    private final int configMask;
    private OriginalSettingsModel model;
    private View returnButton;
    private ImageView previewWindow;
    private int page,backgroundPage=-1;
    private boolean writingPreferences,watchingPreferences;
    private final ArrayList<TextView> sampleRows=new ArrayList<>();
    private final ArrayList<String> sampleLines=new ArrayList<>();
    private int sampleSpeed,sampleLine;
    private long sampleSince;
    private final Runnable typeSample=this::drawSample;
    private final Runnable refresh=new Runnable(){
        @Override public void run(){
            // A worker parameter echo must not detach a pressed button or a
            // captured slider before its action has committed.
            if(controlsBusy()||isAdjusting())postDelayed(this,frameMs);
            else showPage(page);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener=(prefs,key)->{
        if(writingPreferences||PlayerSettings.READING_SIZE.equals(key)||RuntimeSettingsAdapter.isPersistedKey(key))return;
        removeCallbacks(refresh);post(refresh);
    };
    GameSettingsPanel(Context context,GameUiAssets assets,SharedPreferences prefs,GameRunner runner,Runnable click,Runnable close,int width,int height,int configMask,
                      UiPreviewPool.Warmup warmup){
        super(context,assets,"ta_cnf_bg01",click);preferences=prefs;this.runner=runner;this.close=close;this.configMask=configMask;
        previewMutedVoice=assets.config.number("playOffToOnSoundFlag",0)!=0;
        canvas(width,height);model=settingsModel();setContentDescription("环境设置");
        printMode=12;
        if(model.original){uiBackground(assets.config,12,1,warmup);backgroundPage=0;}
        opening(assets.config,false);
    }
    @Override void canvas(int width,int height){
        boolean changed=width>0&&height>0&&(width!=canvasWidth||height!=canvasHeight);
        super.canvas(width,height);
        if(changed&&model!=null){model=settingsModel();if(isAttachedToWindow())showPage(page);}
    }
    private OriginalSettingsModel settingsModel(){return assets.settingsModel(canvasWidth,canvasHeight);}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();
        if(!watchingPreferences){preferences.registerOnSharedPreferenceChangeListener(preferenceListener);watchingPreferences=true;}showPage(page);
    }
    @Override protected void onDetachedFromWindow(){
        removeCallbacks(typeSample);removeCallbacks(refresh);
        if(watchingPreferences){preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener);watchingPreferences=false;}super.onDetachedFromWindow();
    }
    private void write(Runnable change){writingPreferences=true;try{change.run();}finally{writingPreferences=false;}}
    private void writeInt(String key,int value){write(()->preferences.edit().putInt(key,value).apply());}
    private void writeBoolean(String key,boolean value){write(()->preferences.edit().putBoolean(key,value).apply());}
    private void showPage(int selected){
        page=Math.max(0,Math.min(selected,(model.original?model.pages:2)-1));clearControls();
        previewWindow=null;
        removeCallbacks(typeSample);sampleRows.clear();sampleLines.clear();
        sampleSpeed=preferences.getInt("originalSlider.messageSpeed",Math.round(preferences.getInt("textSpeed",1)*4/3f));
        background(model.background(page+1));
        if(!model.original){fallback();return;}
        if(backgroundPage!=page){uiBackground(assets.config,12,page+1);backgroundPage=page;}
        if(model.pageMode==2)for(int i=0;i<model.tabs.size();i++){final int target=i;drawButton(model.tabs.get(i),page==i,"设置第 "+(i+1)+" 页",()->showPage(target));}
        else if(model.pageMode==1)pageArrows(assets.config,page,model.pages,616,16,646,this::showPage);
        for(OriginalSettingsModel.Slider s:model.sliders)if(s.page==page+1)volume(s);
        for(OriginalSettingsModel.CharacterControl c:model.characters)if(c.page==page+1)character(c);
        for(OriginalSettingsModel.Choice c:model.modes)if(c.page==page+1)mode(c);
        for(OriginalSettingsModel.Choice c:model.checks)if(c.page==page+1){boolean value=preferences.getBoolean(c.key,c.defaultValue);
            String state=c.key.equals("continueVoice")?(value?"：继续播放":"：跳过"):(value?"：开":"：关");
            drawButton(c.buttons[0],value,checkLabel(c.key)+state,()->{writeBoolean(c.key,!value);showPage(page);});}
        for(OriginalSettingsModel.Extended group:model.extended)if(group.page==page+1)extended(group);
        if(page+1==model.page("systemVoiceButtonPrintPage",1))systemVoices();
        preview();
        returnButton=back(assets.config,close);
    }
    @Override boolean backOverlay(){return returnButton!=null&&returnButton.performClick();}
    private void systemVoices(){
        int selected=preferences.getInt("systemVoiceSelection",0);
        for(int i=0;i<model.systemVoices.size();i++){
            int theme=i;
            OriginalSettingsModel.Button art=model.systemVoices.get(theme);
            boolean active=selected==theme;
            ButtonArt.Setup setup=art.state(active);
            Runnable cue=()->play(setup,false,previewMutedVoice);
            // STARTCLICK sounds before NUMBER changes the radio selection. An
            // immediate button combines both events and uses the new theme.
            GameButton button=drawButton(art,active,"系统语音主题 "+theme,()->{
                writeInt("systemVoiceSelection",theme);
                if(setup.clickTime==0)cue.run();
                showPage(page);
            },setup.clickTime==0?()->{}:cue);
            button.setEnabled(preferences.getBoolean("systemVoice."+(theme+1)+".enabled",true));
        }
    }
    private String checkLabel(String key){switch(key){case "continueVoice":return "阅读文本时的语音播放";case "autoContinue":return "确认选项后关闭自动播放";
        case "autoSkip":return "确认选项后关闭快进";case "changeReadColor":return "已读台词变色";case "changeSelectColor":return "已选选项变色";default:return key;}}
    private void volume(OriginalSettingsModel.Slider s){
        String enabledKey="enabled."+s.key;boolean enabled=s.button==null||preferences.getBoolean(enabledKey,true);
        if(s.button!=null)drawButton(s.button,enabled,s.kind+(enabled?"：开":"：关"),()->{
            writeBoolean(enabledKey,!enabled);showPage(page);
        },()->play(s.button.state(enabled),s.index==3&&previewMutedVoice,false));
        int maximum=s.index==0?3:s.index==7?2:100,initial=s.index==0||s.index==7?1:s.index==6||s.index==8?100:80;
        int value=Math.max(0,Math.min(preferences.getInt(s.key,initial),maximum));
        String exact="originalSlider."+s.kind;boolean speed=s.index==0||s.index==7;
        int position=speed&&preferences.contains(exact)?preferences.getInt(exact,0):s.fromPreference(value);
        if(s.index==0)sampleSpeed=position;
        String label=s.index==0?"文字速度":s.index==7?"自动播放速度":s.kind;
        ValueBar bar=new ValueBar(getContext(),assets.image(s.image),label,0,s.steps,position,s.width,s.height,20,null,next->{
            if(speed)write(()->preferences.edit().putInt(s.key,s.toPreference(next)).putInt(exact,next).apply());
            else writeInt(s.key,s.toPreference(next));
            if(s.index==0){sampleSpeed=next;restartSample();}
            if(s.index==6&&previewWindow!=null)previewWindow.setAlpha(s.toPreference(next)/100f);
        });
        if(assets.config.number("extSeFlag",0)!=0)
            bar.onCommit(next->runner.playConfigFeedback(s.index,next,s.steps));
        bar.setEnabled(enabled);put(bar,s.x,s.y-10,s.width,s.height+20);
    }
    private void character(OriginalSettingsModel.CharacterControl c){
        if(c.ids.length==0)return;boolean enabled=true;for(int id:c.ids)enabled&=preferences.getBoolean("character."+id+".enabled",true);
        final boolean next=!enabled;
        drawButton(c.button,enabled,"角色组 "+(c.index+1)+(enabled?"：语音开":"：语音关"),()->{
            write(()->{SharedPreferences.Editor edit=preferences.edit();for(int id:c.ids)edit.putBoolean("character."+id+".enabled",next);edit.apply();});showPage(page);
        },()->play(c.button.state(!next),false,previewMutedVoice));
        if(c.hasSlider){
            int value=Math.max(model.characterMin,Math.min(preferences.getInt("character."+c.ids[0]+".steps",0),model.characterMax));
            Bitmap image=assets.image(assets.config.value("filenameCharaVoiceVolume","ta_bar02"));
            ValueBar bar=new ValueBar(getContext(),image,"角色组 "+(c.index+1)+"音量补正",
                model.characterMin,model.characterMax,value,c.width,c.height,20,null,
                steps->write(()->{SharedPreferences.Editor edit=preferences.edit();for(int id:c.ids)edit.putInt("character."+id+".steps",steps);edit.apply();}));
            int configured=assets.config.number("charaVoiceVolumeSE"+(c.index+1),0);
            int cue=configured==0?c.button.off.sound:configured>0?configured-1:-1;
            bar.onCommit(position->runner.playSettingsClick(cue,-1,c.button.on.volumeType,false,false));
            bar.setEnabled(enabled);put(bar,c.x,c.y-10,c.width,c.height+20);
        }
    }
    private void mode(OriginalSettingsModel.Choice c){
        boolean value=preferences.getBoolean(c.key,c.defaultValue);int selected=c.key.equals("skipReadOnly")?(value?0:1):(value?1:0);
        for(int i=0;i<c.buttons.length;i++){final int option=i;
            drawButton(c.buttons[i],selected==i,c.label+"："+(i+1),()->{writeBoolean(c.key,c.key.equals("skipReadOnly")?option==0:option!=0);showPage(page);});}
    }
    private void extended(OriginalSettingsModel.Extended group){
        String key=RuntimeSettingsAdapter.extendedKey(group.parameter());
        int value=preferences.getInt(key,0);
        String label=assets.config.text("exp"+(group.index+1)+(group.check?"Check":"Radio")+"Label","扩展设置 "+(group.index+1));
        if(group.check){
            boolean checked=value!=0;
            drawButton(group.buttons[0],checked,label,()->{writeInt(key,checked?0:1);showPage(page);}).setEnabled(!group.locked(configMask));
            return;
        }
        for(int i=0;i<group.buttons.length;i++){
            final int option=i;
            GameButton button=drawButton(group.buttons[i],value==i,label+" · "+(i+1),
                ()->{writeInt(key,option);showPage(page);});
            button.setEnabled(!group.locked(configMask));
            if(value==i){button.setOnClickListener(null);button.setClickable(false);}
        }
    }
    private void play(ButtonArt.Setup setup,boolean ignoreSwitch,boolean ignoreRole){
        runner.playSettingsClick(setup.sound,setup.voice,setup.volumeType,ignoreSwitch,ignoreRole);
    }
    private TextView previewText(String label,int font,int color){
        TextView view=text(label,font,color);DialoguePanel.textShadow(view,Color.BLACK);return view;
    }
    private void preview(){
        GameUiAssets.Config cfg=assets.config,window=assets.messageWindow;
        if(cfg.number("windowPrintFlag",0)!=0&&page+1==cfg.number("windowPrintPage",1)){
            DialogueLayout.Counts counts=DialogueLayout.counts(window.fields());
            int count=counts.config;
            int selected=Math.max(0,Math.min(count-1,preferences.getInt("messageWindowIndex",0)));
            String[] names=DialogueLayout.names(window.fields(),"filenameWindow","ta_meswin",counts);
            String name=names[DialogueLayout.index(counts,0,selected,0)];Bitmap bitmap=assets.image(name);
            int x=cfg.number("windowPrintX",0),y=cfg.number("windowPrintY",0),w=window.number("sizeX",canvasWidth),h=window.number("sizeY",188);
            if(bitmap!=null){ImageView image=new ImageView(getContext());image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.FIT_XY);previewWindow=image;
                image.setAlpha(Math.max(0,Math.min(100,preferences.getInt("windowOpacity",100)))/100f);put(image,x,y,w,h);
                if(count>1){image.setContentDescription("切换对话框样式");image.setOnClickListener(v->{click.run();writeInt("messageWindowIndex",(selected+1)%count);showPage(page);});}}
            int color=Color.rgb(cfg.number("messageColorR",255)&255,cfg.number("messageColorG",255)&255,cfg.number("messageColorB",255)&255);
            int font=Math.max(1,cfg.number("messageFontSize",24));
            int lineHeight=Math.max(font,cfg.number("messageNextY",30));
            for(int i=0;i<Math.min(32,cfg.number("windowMessageNumber",0));i++){
                String line=cfg.text("windowMessage"+(i+1),"");if(!line.isEmpty()&&!line.startsWith("@"))put(previewText(line,font,color),
                    x+cfg.number("messagePrintX",0)+i*cfg.number("messageNextX",0),y+cfg.number("messagePrintY",0)+i*lineHeight,canvasWidth-x-cfg.number("messagePrintX",0),lineHeight);
            }
            String title=cfg.text("nameWindowMessage","");
            int nameFont=Math.max(1,cfg.number("nameFontSize",cfg.number("messageFontSize",24)));
            if(!title.isEmpty()&&!title.startsWith("@"))put(previewText(title,nameFont,
                Color.rgb(cfg.number("nameColorR",Color.red(color))&255,cfg.number("nameColorG",Color.green(color))&255,cfg.number("nameColorB",Color.blue(color))&255)),
                x+cfg.number("namePrintX",0),y+cfg.number("namePrintY",0),600,nameFont+6);
        }
        if(cfg.number("useSampleText",0)!=0&&page+1==cfg.number("sampleTextPrintPage",1)){
            int x=cfg.number("sampleTextPrintX",0),y=cfg.number("sampleTextPrintY",0);
            int font=Math.max(1,cfg.number("sampleTextFontSize",24));
            int next=Math.max(font,cfg.number("sampleTextNextY",28));
            int color=Color.rgb(cfg.number("sampleTextColorR",255)&255,cfg.number("sampleTextColorG",255)&255,cfg.number("sampleTextColorB",255)&255);
            for(int i=0;i<Math.min(32,cfg.number("sampleTextGyo",1));i++){
                sampleLines.add(cfg.text("sampleText"+(i+1),""));
                TextView row=previewText("",font,color);sampleRows.add(row);
                put(row,x,y+i*next,Math.max(1,canvasWidth-x),next);
            }
            restartSample();
        }
    }
    private void restartSample(){
        removeCallbacks(typeSample);
        if(sampleRows.isEmpty())return;
        for(TextView row:sampleRows)row.setText("");
        sampleSince=-1;sampleLine=0;post(typeSample);
    }
    private void drawSample(){
        if(sampleRows.isEmpty())return;
        if(openingRunning()){postDelayed(typeSample,frameMs);return;}
        long now=SystemClock.uptimeMillis();
        if(sampleSince<0)sampleSince=now;
        int rate=assets.settingsTextRate(sampleSpeed,preferences.getBoolean("autoEnabled",false));
        if(rate<=0)return;
        String line=sampleLines.get(sampleLine);
        int length=line.codePointCount(0,line.length());
        long elapsed=now-sampleSince;
        int shown=(int)Math.min(length,elapsed*rate/1000);
        TextView row=sampleRows.get(sampleLine);
        String visible=line.substring(0,line.offsetByCodePoints(0,shown));
        if(!visible.contentEquals(row.getText()))row.setText(visible);
        if(shown<length){postDelayed(typeSample,frameMs);return;}
        if(sampleLine+1<sampleRows.size()){
            sampleLine++;sampleSince=now;postDelayed(typeSample,frameMs);
        }else if(assets.config.number("sampleTextLoop",0)!=0){
            long remaining=(length*1000L+rate-1)/rate+Math.max(0,assets.config.number("sampleTextWait",5000))-elapsed;
            if(remaining>0)postDelayed(typeSample,remaining);else restartSample();
        }
    }
    private GameButton drawButton(OriginalSettingsModel.Button b,boolean state,String label,Runnable action){
        return drawButton(b,state,label,action,()->play(b.state(state),false,false));
    }
    private GameButton drawButton(OriginalSettingsModel.Button b,boolean state,String label,Runnable action,Runnable cue){
        ButtonArt.Setup setup=b.state(state);
        GameButton.Spec spec=new GameButton.Spec(setup);
        GameButton button=animatedButton(spec,assets.image(spec.image),label,cue,
            action,b.x(state),b.y(state),setup.width,setup.height);
        button.setSelected(state);
        return button;
    }
    private void fallback(){
        for(int tab=0;tab<2;tab++){final int target=tab;chip(tab==0?"音效设置":"文字设置",page==tab,()->showPage(target),45,105+70*tab,210,54);}
        if(page==0){String[] labels={"背景音乐","音效","角色语音","特效语音"},keys={"musicVolume","soundVolume","voiceVolume","effectVoiceVolume"};
            for(int i=0;i<4;i++){final String key=keys[i];put(text(labels[i],28,0xffa4e7ff),330,90+120*i,700,38);
                put(volumeBar("ta_bar01",labels[i],0,100,preferences.getInt(key,80),710,30,v->writeInt(key,v)),330,124+120*i,710,50);}
        }else{options("文字速度","textSpeed",new String[]{"慢","标准","快","立即"},preferences.getInt("textSpeed",1),90,false);
            options("自动播放速度","autoSpeed",new String[]{"慢","标准","快"},preferences.getInt("autoSpeed",1),210,false);
            options("快进范围","skipReadOnly",new String[]{"仅已读","全部文字"},preferences.getBoolean("skipReadOnly",true)?0:1,330,true);}
        returnButton=back(assets.config,close);
    }
    private void options(String label,String key,String[] values,int selected,int y,boolean bool){
        put(text(label,28,0xffa4e7ff),330,y,710,38);int width=(710-14*(values.length-1))/values.length;
        for(int i=0;i<values.length;i++){final int value=i;chip(values[i],selected==i,()->{
            if(bool)writeBoolean(key,value==0);
            else if(key.equals("textSpeed")||key.equals("autoSpeed"))write(()->preferences.edit().putInt(key,value).remove("originalSlider."+(key.equals("textSpeed")?"messageSpeed":"autoSpeed")).apply());
            else writeInt(key,value);showPage(page);
        },330+i*(width+14),y+44,width,54);}
    }
    private ValueBar volumeBar(String image,String label,int min,int max,int value,int w,int h,java.util.function.IntConsumer change){
        return new ValueBar(getContext(),assets.image(image),label,min,max,value,w,h,20,click,change);
    }
    @Override boolean isAdjusting(){for(int i=0;i<getChildCount();i++){View v=getChildAt(i);if(v instanceof ValueBar&&((ValueBar)v).tracking)return true;}return false;}
}
