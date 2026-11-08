package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.tyranor.runtime.systemnnn.GameRunner.Modal.BACK;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.CHOOSE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.GROUP;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PAGE;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PRINT_PARTY;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.PRINT_STATUS;
import static com.tyranor.runtime.systemnnn.GameRunner.Modal.SELECTED;

/** The PrintStatus graphs and the two CCommonPrintParty layouts. */
final class GameStatusPartyPanel extends GameUiPanel {
    interface Result { void action(int action,int value); }

    private static final int MAX_FIELDS=256,DATA_ROW=10000;
    private final GameUiAssets.Config config,faces;
    private final boolean status,legacy,independent;
    private final Result result;
    private final int effectFrames;
    private final Map<Integer,GameRunner.Modal.Row> options=new HashMap<>();
    private final Map<String,String> values=new HashMap<>();
    private final List<GameButton> effectsLocked=new ArrayList<>();
    private GameRunner.Modal modal;
    private ArtLayer layer;
    private GameButton back;
    private boolean foreground=true,pending;
    private long clockStart=-1,clockElapsed;

    GameStatusPartyPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,int width,int height,
                         Runnable click,Result result){
        this(context,assets,modal,width,height,click,result,false);
    }

    GameStatusPartyPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,int width,int height){
        this(context,assets,modal,width,height,()->{},null,true);
    }

    private GameStatusPartyPanel(Context context,GameUiAssets assets,GameRunner.Modal modal,int width,int height,
                                 Runnable click,Result result,boolean independent){
        super(context,assets,independent?null:modal.assets.config.value("filenameBG",""),click);
        status=modal.mode==PRINT_STATUS;
        if(!status&&modal.mode!=PRINT_PARTY)throw new IllegalArgumentException("Unsupported status mode "+modal.mode);
        this.independent=independent;
        config=modal.assets.config;
        faces=modal.assets.variant;
        legacy=!status&&config.number("oldVersion",0)!=0;
        effectFrames=status&&!independent?Math.max(0,config.number("effectTime",0)):0;
        this.result=result;
        canvas(width,height);
        if(independent){
            transparentBackground();
            setClickable(false);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }else{
            opening(config,false);
            uiBackground(config,modal.mode,1);
        }
        update(modal);
    }

    void update(GameRunner.Modal next){
        GameRunner.Modal previous=modal;
        modal=next;
        if(independent&&sameIndependentData(previous,next))return;
        pending=false;
        options.clear();
        values.clear();
        for(GameRunner.Modal.Row row:next.rows){
            if(row.id>=DATA_ROW)values.put(row.label,row.detail);
            else options.put(row.id,row);
        }

        if(independent&&layer!=null)layer.parts.clear();
        else{
            clearControls();
            effectsLocked.clear();
            layer=new ArtLayer(getContext());
            put(layer,0,0,canvasWidth,canvasHeight);
        }
        if(status)graphs();
        else if(!legacy&&faces!=null)modernParty();
        else if(legacy&&!independent)legacyParty();

        if(independent){
            layer.invalidate();
            return;
        }

        if(status||legacy)addBack();
        if(status||!legacy){
            addPlaceControls();
            addRadios();
        }
        if(!status&&!legacy&&faces!=null)addPartyControls();
        if(!status&&!legacy)addBack();
    }

    private static boolean sameIndependentData(GameRunner.Modal previous,GameRunner.Modal next){
        if(previous==null||previous.mode!=next.mode||previous.assets!=next.assets||previous.group!=next.group||
            previous.rows.length!=next.rows.length)return false;
        for(int i=0;i<next.rows.length;i++){
            GameRunner.Modal.Row before=previous.rows[i],after=next.rows[i];
            if(before.id!=after.id||!before.label.equals(after.label)||!before.detail.equals(after.detail))return false;
        }
        return true;
    }

    @Override Bitmap sprite(String name){return find(assets,modal,name);}

    private static Bitmap find(GameUiAssets assets,GameRunner.Modal modal,String name){
        if(name==null||name.isEmpty())return null;
        Bitmap picture=modal.assets.images.get(GameUiAssets.systemImageKey(name));
        return picture==null?assets.image(name):picture;
    }

    static String statusPartyFailure(GameUiAssets assets,GameRunner.Modal modal){
        if(modal.mode!=PRINT_STATUS&&modal.mode!=PRINT_PARTY)return null;
        if(modal.assets==null)return "Missing original status setup";
        GameUiAssets.Config cfg=modal.assets.config;
        if(UiPreviewPool.Request.read(cfg,modal.mode,1)!=null||find(assets,modal,cfg.value("filenameBG",""))!=null)
            return null;
        GameUiAssets.Config back=backSetup(assets,cfg);
        if(find(assets,modal,back.value("BackFileName","ta_backButton"))!=null)return null;
        if(modal.mode==PRINT_STATUS&&cfg.number("setNumber",1)>0){
            for(GameRunner.Modal.Row row:modal.rows)
                if(row.id>=DATA_ROW&&row.label.startsWith("set")&&row.label.endsWith("varname"))return null;
        }
        if(modal.mode==PRINT_PARTY){
            boolean old=cfg.number("oldVersion",0)!=0;
            GameUiAssets.Config source=old?cfg:modal.assets.variant;
            if(source!=null&&!source.fields().isEmpty()){
                for(String file:source.imageNames())
                    if(find(assets,modal,file)!=null)return null;
            }
        }
        return "No displayable original status resources";
    }

    @Override void uiActive(boolean active){
        super.uiActive(active);
        if(!active)pauseClock();
        foreground=active;
        if(active&&layer!=null)layer.invalidate();
    }

    @Override void cancelLeave(){
        super.cancelLeave();
        pending=false;
        if(layer!=null)layer.invalidate();
    }

    @Override boolean backOverlay(){
        if(independent)return false;
        if(ready(false)){
            if(back!=null)back.performClick();
            else send(BACK,0,false);
        }
        return true;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event){
        return !independent&&(pending||super.dispatchTouchEvent(event));
    }

    private long frames(){
        long elapsed=clockElapsed;
        if(clockStart>=0)elapsed+=SystemClock.uptimeMillis()-clockStart;
        return elapsed/frameMs;
    }

    private void pauseClock(){
        if(clockStart<0)return;
        clockElapsed+=SystemClock.uptimeMillis()-clockStart;
        clockStart=-1;
    }

    private boolean ready(boolean afterEffect){
        return foreground&&!pending&&!controlsBusy()&&activeFrameReady()&&!openingRunning()&&
            (!afterEffect||frames()>=effectFrames);
    }

    private void send(int action,int value,boolean afterEffect){
        if(!ready(afterEffect))return;
        pending=true;
        result.action(action,value);
    }

    private static GameUiAssets.Config backSetup(GameUiAssets assets,GameUiAssets.Config cfg){
        return cfg.number("backButtonType",0)==1||cfg.number("useCommonBackSetup",0)!=0?assets.game:cfg;
    }

    private void addBack(){
        GameUiAssets.Config cfg=backSetup(assets,config);
        int x=cfg.number("BackPrintX",0),y=cfg.number("BackPrintY",0);
        if(config.number("backButtonType",0)==0&&cfg!=config){
            x=config.number("BackPrintX",x);
            y=config.number("BackPrintY",y);
        }
        back=control(new ButtonArt.Setup(cfg,"Back",null,false),"ta_backButton","返回",BACK,0,x,y,false);
    }

    private GameButton control(ButtonArt.Setup setup,String fallback,String label,int action,int value,
                               int x,int y,boolean afterEffect){
        GameButton.Spec spec=new GameButton.Spec(setup);
        Bitmap image=sprite(spec.image.isEmpty()?fallback:spec.image);
        SpriteAnimation normal=spec.states[0];
        if(image==null||normal.x<0||normal.y<0||normal.x>=image.getWidth()||normal.y>=image.getHeight())return null;
        GameButton button=animatedButton(spec,image,label,()->{
            if(ready(afterEffect))sound(spec.sound,spec.voice);
        },()->send(action,value,afterEffect),x,y,setup.width,setup.height);
        if(afterEffect&&frames()<effectFrames){
            button.setClickable(false);
            button.setFocusable(false);
            effectsLocked.add(button);
        }
        return button;
    }

    private void check(String key,boolean checked,String label,int id,int x,int y){
        ButtonArt.Setup first=new ButtonArt.Setup(config,key+"1",null,false);
        ButtonArt.Setup state=checked?new ButtonArt.Setup(config,key+"2",first,false):first;
        control(state,"",label,CHOOSE,id,x,y,true);
    }

    private void addPlaceControls(){
        int count=setCount();
        for(int id=1;id<=count;id++){
            GameRunner.Modal.Row row=options.get(id);
            if(row==null||!row.enabled())continue;
            Point at=base(place(id));
            check("onOff"+id+"Button",(row.flags&SELECTED)!=0,"显示位置 "+id,id,
                at.x+config.number("checkPrintDeltaX",100),at.y+config.number("checkPrintDeltaY",0));
        }
        for(int id=1;id<=count;id++){
            GameRunner.Modal.Row row=options.get(id);
            if(row==null||!row.enabled())continue;
            int place=place(id);
            Point at=base(place);
            ButtonArt.Setup up=new ButtonArt.Setup(config,"up"+id,null,false);
            ButtonArt.Setup down=new ButtonArt.Setup(config,"down"+id,up,false);
            if(place>0)control(up,"","上移位置 "+id,PAGE,-id,
                at.x+config.number("upPrintDeltaX",150),at.y+config.number("upPrintDeltaY",0),true);
            if(place+1<count)control(down,"","下移位置 "+id,PAGE,id,
                at.x+config.number("downPrintDeltaX",150),at.y+config.number("downPrintDeltaY",50),true);
        }
    }

    private void addRadios(){
        int count=fieldCount(config,"setPlaceSetNumber",1);
        if(count<=1||config.number("useRadio",0)==0)return;
        ButtonArt.Setup previous=null;
        int prevX=0,prevY=0;
        boolean resetSource=assets.game.number("notSrcCopyFlagEnable",0)!=0;
        for(int id=1;id<=count;id++){
            String key="radio"+id+"Button";
            ButtonArt.Setup first=new ButtonArt.Setup(config,key+"1",previous,resetSource);
            ButtonArt.Setup second=new ButtonArt.Setup(config,key+"2",first,false);
            int x=config.number(key+"1PrintX",prevX),y=config.number(key+"1PrintY",prevY);
            int checkedX=config.number(key+"2PrintX",x),checkedY=config.number(key+"2PrintY",y);
            boolean selected=id==preset();
            control(selected?second:first,"","布局 "+id,GROUP,id-1,selected?checkedX:x,selected?checkedY:y,true);
            prevX=checkedX+second.width;
            prevY=checkedY;
            previous=second;
        }
    }

    private void addPartyControls(){
        int parameters=1+fieldCount(faces,"textNumber",0)+fieldCount(faces,"suujiNumber",0)+fieldCount(faces,"barNumber",0);
        for(int id=1;id<=Math.min(MAX_FIELDS,parameters);id++){
            GameRunner.Modal.Row row=options.get(-id);
            if(row==null||!row.enabled())continue;
            String key="paramOff"+id+"Button";
            boolean checked=(row.flags&SELECTED)!=0;
            int x=config.number(key+"1PrintX",0),y=config.number(key+"1PrintY",0);
            if(checked){
                x=config.number(key+"2PrintX",x);
                y=config.number(key+"2PrintY",y);
            }
            check(key,checked,"显示参数 "+id,-id,x,y);
        }
        for(int id=1;id<=fieldCount(faces,"etcNumber",0);id++){
            GameRunner.Modal.Row row=options.get(-1000-id);
            if(row==null||!row.enabled())continue;
            Point at=extraBase(id);
            // CCommonPrintParty uses the default X offset and reads only the configurable Y offset.
            check("etc"+id+"OnOffButton",integer(row.detail,0)<=0,"显示附加参数 "+id,-1000-id,
                at.x+100,at.y+config.number("etcPrintDeltaY",0));
        }
    }

    private int setCount(){return fieldCount(config,"setNumber",status?1:0);}

    private int preset(){return clamp(modal.group+1,1,Math.max(1,fieldCount(config,"setPlaceSetNumber",1)));}

    private int place(int id){
        int order=optionValue(id,id);
        return (int)Math.max(0,Math.min(setCount()-1L,Math.abs((long)order)-1));
    }

    private int optionValue(int id,int fallback){
        GameRunner.Modal.Row row=options.get(id);
        return row==null?fallback:integer(row.detail,fallback);
    }

    private boolean parameterVisible(int id){return !independent||optionValue(-id,0)<=0;}

    private Point base(int place){
        int preset=preset(),position=place+1;
        if(status){
            String key="setPlaceSet";
            return new Point(config.number(key+"X"+preset+"_"+position,config.number("setPlaceX"+position,100*place)),
                config.number(key+"Y"+preset+"_"+position,config.number("setPlaceY"+position,0)));
        }
        String key="set"+preset+"_"+position;
        return new Point(config.number(key+"PrintX",100*(preset-1)),config.number(key+"PrintY",100*place));
    }

    private Point extraBase(int id){
        int preset=preset();
        String key="set"+preset+"_etc"+id;
        return new Point(config.number(key+"PrintX",50+100*(preset-1)),config.number(key+"PrintY",50+100*(id-1)));
    }

    private int value(String key,int fallback){
        return integer(values.get(key.toLowerCase(Locale.ROOT)),fallback);
    }

    private String replacement(int type,int index){
        if(type==2)return values.get("local"+index);
        if(type==3)return values.get("system"+index);
        return null;
    }

    private static int integer(String text,int fallback){
        if(text==null||text.isEmpty())return fallback;
        try{return Integer.parseInt(text);}
        catch(NumberFormatException invalid){return fallback;}
    }

    private static int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    private static int fieldCount(GameUiAssets.Config cfg,String key,int initial){
        return clamp(cfg.number(key,initial),0,MAX_FIELDS);
    }
    private static int color(GameUiAssets.Config cfg,String key,int previous){
        return Color.argb(clamp(cfg.number(key+"A",Color.alpha(previous)),0,255),
            clamp(cfg.number(key+"R",Color.red(previous)),0,255),
            clamp(cfg.number(key+"G",Color.green(previous)),0,255),
            clamp(cfg.number(key+"B",Color.blue(previous)),0,255));
    }

    private void graphs(){
        for(int id=1;id<=setCount();id++){
            if(independent&&optionValue(id,id)<=0)continue;
            String key="set"+id;
            Point at=base(place(id));
            int count=fieldCount(config,key+"GraphNumber",1);
            if(count!=0)layer.add(new Graph(at,key,count));
        }
    }

    private void legacyParty(){
        int count=fieldCount(config,"printNumber",1);
        int members=fieldCount(config,"partyNumber",count);
        int statuses=fieldCount(config,"statusNumber",1);
        LegacyField[] fields=new LegacyField[statuses+1];
        int type=3,font=24,ink=Color.WHITE;
        LegacyDigits digits=new LegacyDigits();
        for(int id=0;id<=statuses;id++){
            String key="status"+id;
            type=config.number(key+"Type",id==1?1:type);
            int x=config.number(key+"PrintX",0),y=config.number(key+"PrintY",0);
            Digits number=null;
            if(type==1)number=digits.read(key);
            else if(type==2||type==3){
                font=config.number(key+"FontSize",font);
                ink=color(config,key+"FontColor",ink);
            }
            fields[id]=new LegacyField(type,x,y,font,ink,number);
        }
        Bitmap plate=sprite(config.value("filenamePlate",""));
        int nextX=config.number("partyAreaNextX",0),nextY=config.number("partyAreaNextY",config.number("partyAreaSizeY",150));
        int x=0,y=0;
        for(int id=1;id<=count;id++){
            x=config.number("party"+id+"PrintX",x);
            y=config.number("party"+id+"PrintY",y);
            int member=value("print"+id+"Var",id);
            if(member>0&&member<=members){
                layer.image(plate,x,y);
                Bitmap portrait=sprite(config.value("party"+member+"Filename",""));
                layer.image(portrait,x+fields[0].x,y+fields[0].y);
                for(int k=1;k<=statuses;k++){
                    LegacyField field=fields[k];
                    String key="party"+member+"_"+k;
                    if(field.type==1)layer.number(field.digits,x+field.x,y+field.y,value(key+"Var",-1));
                    if(field.type==2||field.type==3)
                        layer.text(replacement(field.type,config.number(key+"TextNumber",-1)),
                            x+field.x,y+field.y,field.font,field.ink);
                }
            }
            x+=nextX;
            y+=nextY;
        }
        x=fields[statuses].x;
        y=fields[statuses].y;
        for(int id=1;id<=fieldCount(config,"etcNumber",0);id++){
            String key="etc"+id;
            x=config.number(key+"PrintX",x);
            y=config.number(key+"PrintY",y);
            layer.number(digits.read(key),x,y,value(key+"Var",0));
        }
    }

    private void modernParty(){
        if(value("statusPrintVarName",0)==0)return;
        Bitmap plate=sprite(faces.value("plateFilename",""));
        int members=fieldCount(faces,"charaNumber",1);
        int textCount=fieldCount(faces,"textNumber",0),digitCount=fieldCount(faces,"suujiNumber",0);
        int barCount=fieldCount(faces,"barNumber",0);
        // The original full-screen editor previews hidden fields as well.
        for(int slot=1;slot<=Math.min(setCount(),members);slot++){
            if(independent&&optionValue(slot,slot)<=0)continue;
            Point at=base(place(slot));
            layer.image(plate,at.x,at.y);
            int member=value("status"+slot+"PlayerVarName",0);
            if(member<1||member>members)continue;
            layer.image(plate,at.x,at.y);
            if(parameterVisible(1))addFace(at,member);
            for(int id=1;id<=textCount;id++){
                if(!parameterVisible(1+id))continue;
                String key="text"+id;
                int index=value(key+"_"+member+"VarName",0);
                int type=faces.number(key+"Type",0);
                String label=replacement(type,index);
                int count=fieldCount(faces,key+"MessageNumber",1);
                if(label==null&&count>0&&index>=0&&index<=count)
                    label=faces.text(key+"Message"+member+"_"+Math.max(1,index),"");
                layer.text(label,at.x+faces.number(key+"PrintX",0),at.y+faces.number(key+"PrintY",0),
                    faces.number(key+"FontSize",16),color(faces,key+"Color",Color.WHITE));
            }
            for(int id=1;id<=digitCount;id++){
                if(!parameterVisible(1+textCount+id))continue;
                String key="suuji"+id;
                layer.number(digits(faces,key),at.x+faces.number(key+"PrintX",0),at.y+faces.number(key+"PrintY",0),
                    value("data"+id+"_"+member+"VarName",0));
            }
            for(int id=1;id<=barCount;id++)
                if(parameterVisible(1+textCount+digitCount+id))addBar(at,id,member);
        }
        int deltaX=0,deltaY=0;
        for(int id=1;id<=fieldCount(faces,"etcNumber",0);id++){
            String key="etc"+id;
            deltaX=faces.number(key+"PrintX",deltaX);
            deltaY=faces.number(key+"PrintY",deltaY);
            if(independent&&optionValue(-1000-id,0)!=0)continue;
            Point at=extraBase(id);
            layer.image(sprite(faces.value(key+"PlateFilename","")),at.x,at.y);
            layer.number(digits(faces,key+"suuji"),at.x+deltaX,at.y+deltaY,value(key+"VarName",0));
        }
    }

    private void addFace(Point at,int member){
        String key="face"+member+"_1";
        Bitmap image=sprite(faces.value("filename"+key,"ta_anime"));
        if(image==null)return;
        int width=dimension(faces.number(key+"SizeX",16)),height=dimension(faces.number(key+"SizeY",16));
        if(width<=0||height<=0)return;
        SpriteAnimation animation=new SpriteAnimation(0,0,width,height,faces.number(key+"Type",1),
            faces.number(key+"Pattern",1),faces.number(key+"Speed",1),true);
        int x=at.x+faces.number("facePrintX",0),y=at.y+faces.number("facePrintY",0);
        layer.add((c,frames,percent)->{
            layer.dest.set(x,y,x+width,y+height);
            animation.draw(c,image,layer.dest,layer.paint,frames+1,255);
            return animation.active(frames+1);
        });
    }

    private void addBar(Point at,int id,int member){
        String key="bar"+id;
        int x=at.x+faces.number(key+"BasePrintX",0),y=at.y+faces.number(key+"BasePrintY",0);
        layer.image(sprite(faces.value(key+"BaseFilename","")),x,y);
        int max=value(key+"_"+member+"MaxVarName",100);
        int width=dimension(faces.number(key+"SizeX",50)),height=dimension(faces.number(key+"SizeY",10));
        Bitmap fill=sprite(faces.value(key+"Filename",""));
        if(fill!=null&&max>0){
            int drawn=scaled(width,value(key+"_"+member+"VarName",0),max,100);
            layer.crop(fill,x+faces.number(key+"PrintX",2),y+faces.number(key+"PrintY",2),0,0,drawn,height);
        }
    }

    private Digits digits(GameUiAssets.Config cfg,String key){
        int w=dimension(cfg.number(key+"FontSizeX",16)),h=dimension(cfg.number(key+"FontSizeY",16));
        String name=cfg.value("filename"+key,"");
        Bitmap image=cfg.number(key+"TransFlag",1)==0?
            modal.assets.opaqueImages.get(GameUiAssets.systemImageKey(name)):sprite(name);
        return new Digits(image,w,h,cfg.number(key+"NextX",w),
            cfg.number(key+"Keta",2),cfg.number(key+"LeftFlag",0)!=0,cfg.number(key+"UpZeroFlag",0)!=0);
    }
    static java.util.Set<String> opaqueDigits(GameUiAssets.Config config){
        java.util.Set<String> names=new java.util.LinkedHashSet<>();
        for(String key:config.fields().keySet()){
            if(!key.matches("filename(?:suuji[0-9]+|etc[0-9]+suuji)"))continue;
            if(config.number(key.substring(8)+"TransFlag",1)==0)names.add(config.value(key,""));
        }
        return names;
    }

    private static final class LegacyField {
        final int type,x,y,font,ink;
        final Digits digits;
        LegacyField(int type,int x,int y,int font,int ink,Digits digits){
            this.type=type;this.x=x;this.y=y;this.font=font;this.ink=ink;this.digits=digits;
        }
    }

    private final class LegacyDigits {
        int w=16,h=16,keta=3;
        boolean left,zero;
        String name="ta_suuji";
        Digits read(String key){
            w=dimension(config.number(key+"SuujiSizeX",w));
            h=dimension(config.number(key+"SuujiSizeY",h));
            int next=config.number(key+"SuujiNextX",w);
            keta=config.number(key+"Keta",keta);
            left=config.number(key+"LeftFlag",left?1:0)!=0;
            zero=config.number(key+"UpZero",zero?1:0)!=0;
            name=config.value(key+"SuujiFilename",name);
            return new Digits(sprite(name),w,h,next,keta,left,zero);
        }
    }

    private static final class Digits {
        final Bitmap image;
        final int w,h,next,count;
        final boolean left,zero;
        Digits(Bitmap image,int w,int h,int next,int count,boolean left,boolean zero){
            this.image=image;this.w=w;this.h=h;this.next=next;this.count=clamp(count,0,10);
            this.left=left;this.zero=zero;
        }
    }

    private static final class GraphValue {
        final int x,y,w,h,maximum,data,color;
        final Bitmap image;
        GraphValue(int x,int y,int w,int h,int maximum,int data,int color,Bitmap image){
            this.x=x;this.y=y;this.w=w;this.h=h;this.maximum=maximum;this.data=data;this.color=color;this.image=image;
        }
    }

    private final class Graph implements Draw {
        final GraphValue[] parts;
        final int type,lineSize;
        final boolean pictures,bySum;
        final Path shape=new Path();
        final RectF arc=new RectF();

        Graph(Point at,String key,int count){
            type=config.number(key+"GraphType",1);
            lineSize=clamp(config.number(key+"LineSize",3),0,64);
            pictures=config.number(key+"PicFlag",0)!=0;
            bySum=config.number(key+"PerFlag",0)!=0;
            parts=new GraphValue[count];
            int w=64,h=64,maximum=100,ink=Color.WHITE,x=0,y=0;
            int dx=config.number(key+"NextX",0),dy=config.number(key+"NextY",0);
            String image="";
            for(int i=0;i<count;i++){
                String item=key+"_"+(i+1);
                w=dimension(config.number(item+"SizeX",w));
                h=dimension(config.number(item+"SizeY",h));
                maximum=config.number(item+"Max",maximum);
                ink=color(config,item+"Color",ink);
                x=config.number(item+"PrintX",x);
                y=config.number(item+"PrintY",y);
                image=config.value(item+"Filename",image);
                parts[i]=new GraphValue(at.x+x,at.y+y,w,h,maximum,value(item+"VarName",0),ink,
                    pictures?sprite(image):null);
                x+=dx;
                y+=dy;
            }
        }

        @Override public boolean draw(Canvas c,long frames,int percent){
            switch(type){
                case 1:case 2:bars(c,percent);break;
                case 3:pie(c);break;
                case 4:line(c);break;
                case 5:radar(c);break;
                default:break;
            }
            return false;
        }

        private void bars(Canvas c,int percent){
            for(GraphValue v:parts){
                int w=type==1?scaled(v.w,v.data,v.maximum,percent):v.w;
                int h=type==2?scaled(v.h,v.data,v.maximum,percent):v.h;
                if(w<=0||h<=0)continue;
                int y=type==2?v.y-h+1:v.y;
                if(pictures)layer.blit(c,v.image,v.x,y,0,type==2?v.h-h:0,w,h);
                else{
                    layer.paint.setColor(v.color);
                    int alpha=(Color.alpha(v.color)*100)/255;
                    layer.paint.setAlpha(alpha*255/100);
                    c.drawRect(v.x,y,v.x+w,y+h,layer.paint);
                    layer.paint.setAlpha(255);
                }
            }
        }

        private void pie(Canvas c){
            long max=parts[0].maximum;
            if(bySum){
                max=0;
                for(GraphValue part:parts)max+=part.data;
                max=Math.max(1,max);
            }
            if(max<=0)return;
            int x=parts[0].x,y=parts[0].y;
            long start=0;
            for(GraphValue v:parts){
                long end=start+v.data;
                int radius=v.h/2-1;
                if(radius>0&&end>start&&start<max&&end>0){
                    int centerX=x+v.w/2,centerY=y+v.h/2;
                    arc.set(centerX-radius,centerY-radius,centerX+radius,centerY+radius);
                    float a=(float)(360.0*Math.max(0,start)/max)-90;
                    float sweep=(float)(360.0*(Math.min(max,end)-Math.max(0,start))/max);
                    shape.rewind();
                    if(sweep>=360)shape.addOval(arc,Path.Direction.CW);
                    else{
                        shape.moveTo(centerX,centerY);
                        shape.arcTo(arc,a,sweep,false);
                        shape.close();
                    }
                    drawShape(c,v,x,y);
                }
                start=end;
            }
        }

        private void line(Canvas c){
            for(int i=0;i<parts.length;i++){
                GraphValue v=parts[i];
                int y=v.y-offset(v.h,v.data,Math.max(1,v.maximum));
                if(i+1<parts.length&&lineSize>0){
                    GraphValue next=parts[i+1];
                    int y2=next.y-offset(next.h,next.data,Math.max(1,v.maximum));
                    layer.paint.setColor(v.color|0xff000000);
                    layer.paint.setStrokeWidth(lineSize);
                    layer.paint.setStrokeCap(Paint.Cap.SQUARE);
                    c.drawLine(v.x,y,next.x,y2,layer.paint);
                }
                if(v.image!=null)layer.blit(c,v.image,v.x-v.image.getWidth()/2,y-v.image.getHeight()/2,
                    0,0,v.image.getWidth(),v.image.getHeight());
            }
        }

        private void radar(Canvas c){
            if(parts.length<3)return;
            int x=parts[0].x,y=parts[0].y;
            double step=2*Math.PI/parts.length;
            for(int i=0;i<parts.length;i++){
                GraphValue v=parts[i];
                if(v.maximum==0)continue;
                int cx=x+v.w/2,cy=y+v.h/2;
                double r=v.w/2.0-1;
                if(r<=0)continue;
                double r1=Math.min(r,r*v.data/v.maximum);
                double r2=Math.min(r,r*parts[(i+1)%parts.length].data/v.maximum);
                double a=step*i-Math.PI/2,b=a+step;
                shape.rewind();
                shape.moveTo(cx,cy);
                shape.lineTo(cx+(int)(r1*Math.cos(a)),cy+(int)(r1*Math.sin(a)));
                shape.lineTo(cx+(int)(r2*Math.cos(b)),cy+(int)(r2*Math.sin(b)));
                shape.close();
                drawShape(c,v,x,y);
            }
        }

        private void drawShape(Canvas c,GraphValue v,int x,int y){
            if(!pictures){
                layer.paint.setColor(v.color);
                c.drawPath(shape,layer.paint);
                layer.paint.setAlpha(255);
            }else if(v.image!=null){
                int saved=c.save();
                c.clipPath(shape);
                layer.blit(c,v.image,x,y,0,0,v.w,v.h);
                c.restoreToCount(saved);
            }
        }
    }

    private static int dimension(int value){return clamp(value,0,4096);}

    private static int scaled(int extent,int value,int maximum,int percent){
        if(maximum==0)return 0;
        return clamp((int)((double)extent*value*percent/(maximum*100.0)),0,32768);
    }

    private static int offset(int extent,int value,int maximum){
        return clamp((int)((double)extent*value/maximum),-32768,32768);
    }

    private interface Draw { boolean draw(Canvas c,long frames,int percent); }

    private final class ArtLayer extends View {
        final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        final Rect src=new Rect();
        final RectF dest=new RectF();
        final List<Draw> parts=new ArrayList<>();

        ArtLayer(Context context){
            super(context);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void add(Draw part){parts.add(part);}

        void image(Bitmap image,int x,int y){
            if(image!=null)crop(image,x,y,0,0,image.getWidth(),image.getHeight());
        }

        void crop(Bitmap image,int x,int y,int sx,int sy,int w,int h){
            if(w>0&&h>0)add((c,frames,percent)->{blit(c,image,x,y,sx,sy,w,h);return false;});
        }

        void text(String value,int x,int y,int size,int ink){
            if(value==null||value.isEmpty()||size<=0)return;
            add((c,frames,percent)->{
                paint.setAntiAlias(true);
                paint.setColor(ink);
                paint.setTextSize(Math.min(4096,size));
                c.drawText(value,x,y-paint.ascent(),paint);
                paint.setAlpha(255);
                paint.setAntiAlias(false);
                return false;
            });
        }

        void number(Digits digits,int x,int y,int value){
            if(digits==null||digits.image==null||digits.count==0||value<0||digits.w<=0||digits.h<=0)return;
            int remaining=value;
            if(digits.left){
                long divisor=1;
                for(int i=1;i<digits.count;i++)divisor*=10;
                boolean started=false;
                for(int i=0;i<digits.count;i++){
                    int digit=(int)(remaining/divisor);
                    if(digit!=0||started||digits.zero||i==digits.count-1){
                        glyph(digits,x,y,digit);
                        started=true;
                        x+=digits.next;
                    }
                    remaining=(int)(remaining%divisor);
                    divisor/=10;
                }
            }else{
                x+=(digits.count-1)*digits.next;
                for(int i=0;i<digits.count;i++){
                    glyph(digits,x,y,remaining%10);
                    remaining/=10;
                    if(remaining==0&&!digits.zero)break;
                    x-=digits.next;
                }
            }
        }

        void glyph(Digits digits,int x,int y,int digit){
            long sx=(long)digit*digits.w;
            if(sx>=digits.image.getWidth())return;
            crop(digits.image,x,y,(int)sx,0,digits.w,digits.h);
        }

        void blit(Canvas c,Bitmap image,int x,int y,int sx,int sy,int w,int h){
            if(image==null||w<=0||h<=0)return;
            int left=Math.max(0,sx),top=Math.max(0,sy);
            int right=Math.min(image.getWidth(),sx+w),bottom=Math.min(image.getHeight(),sy+h);
            if(left>=right||top>=bottom)return;
            src.set(left,top,right,bottom);
            dest.set(x+left-sx,y+top-sy,x+right-sx,y+bottom-sy);
            c.drawBitmap(image,src,dest,paint);
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            boolean moving=foreground&&activeFrameReady();
            if(moving&&clockStart<0)clockStart=SystemClock.uptimeMillis();
            long frames=frames();
            int percent=effectFrames==0?100:(int)Math.min(100,100L*frames/effectFrames);
            if(percent==100&&!effectsLocked.isEmpty()){
                for(GameButton button:effectsLocked){
                    button.setClickable(true);
                    button.setFocusable(true);
                }
                effectsLocked.clear();
            }
            int saved=c.save();
            c.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            boolean animated=false;
            for(Draw part:parts)animated|=part.draw(c,frames,percent);
            c.restoreToCount(saved);
            if(moving&&(animated||percent<100))postInvalidateOnAnimation();
        }
    }
}
