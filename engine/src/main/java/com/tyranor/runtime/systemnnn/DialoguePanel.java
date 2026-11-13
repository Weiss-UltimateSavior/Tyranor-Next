package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.Gravity;
import android.view.MotionEvent;
import android.util.TypedValue;
import android.text.method.ScrollingMovementMethod;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Layout;
import android.graphics.text.LineBreaker;
import android.text.style.LeadingMarginSpan;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Message and name windows use the game's logical canvas. */
final class DialoguePanel extends FrameLayout {
    final DialogueTextView speaker,body;
    private final WindowView window,nameWindow;
    private final MessageCursor cursor;
    private final PortraitLayer belowWindow,aboveWindow;
    private final CutinLayer cutinLayer;
    private DialogueDecorations decorations=DialogueDecorations.EMPTY;
    private boolean drawingPlane,includeCursor,directDraw;
    private DialogueStyle style=DialogueStyle.fallback();
    private PlayerSettings settings=PlayerSettings.defaults();
    private boolean hasSpeaker,showNameWindow,wasRead,longPrint,showWindow=true,storyBody,passive;
    private int contentRight=1280;
    private int canvasWidth=1280,canvasHeight=720,windowType,fontType,configType;
    private DialogueLayout.Text layout=style.layouts[0];
    private String message="",speakerName="";
    private int revealed,lastLogicalRow,textOrigin,textRight;
    private float textScale=-1;
    private Spannable rendered;
    private final DialogueTextView.HiddenText hiddenText=new DialogueTextView.HiddenText();

    DialoguePanel(Context context,OnClickListener click) {
        super(context);
        belowWindow=new PortraitLayer(1);addView(belowWindow,new LayoutParams(-1,-1));
        window=window(false); nameWindow=window(true);
        aboveWindow=new PortraitLayer(2);addView(aboveWindow,new LayoutParams(-1,-1));
        speaker=text(new DialogueTextView(context));speaker.setGravity(Gravity.TOP|Gravity.LEFT);speaker.setTextDirection(TEXT_DIRECTION_LTR);speaker.setSingleLine(true);
        body=text(new DialogueTextView(context));body.setGravity(Gravity.TOP|Gravity.LEFT);body.setTextDirection(TEXT_DIRECTION_LTR);
        // Scripted row positions use the same line advance for every glyph.
        if(Build.VERSION.SDK_INT>=28)body.setFallbackLineSpacing(false);
        if(Build.VERSION.SDK_INT>=35)body.setLocalePreferredLineHeightForMinimumUsed(false);
        body.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);body.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        body.setAccessibilityDelegate(new AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.setText(message.substring(0,revealed));
            }
        });
        body.setMovementMethod(new ScrollingMovementMethod());body.setVerticalScrollBarEnabled(true);
        window.setOnClickListener(click); nameWindow.setOnClickListener(click);
        speaker.setOnClickListener(click); body.setOnClickListener(click);
        cutinLayer=new CutinLayer(click);addView(cutinLayer,new LayoutParams(-1,-1));
        cursor=new MessageCursor();addView(cursor,new LayoutParams(-1,-1));
    }
    private WindowView window(boolean name) {
        WindowView v=new WindowView(getContext(),name);
        addView(v,new LayoutParams(1,1));
        return v;
    }
    void directDraw(boolean value){
        if(directDraw!=value){directDraw=value;invalidate();}
    }
    boolean waitingCursorVisible(){return cursor.getVisibility()==VISIBLE;}
    boolean animating(){
        return getVisibility()!=GONE&&(cursor.moving()||belowWindow.moving()||aboveWindow.moving());
    }
    void drawPlane(Canvas canvas,boolean withCursor){
        if(getVisibility()==GONE)return;
        drawingPlane=true;includeCursor=withCursor;
        try{draw(canvas);}finally{drawingPlane=false;includeCursor=false;}
    }
    @Override protected void dispatchDraw(Canvas canvas){
        if(drawingPlane||directDraw)super.dispatchDraw(canvas);
    }
    @Override protected boolean drawChild(Canvas canvas,View child,long time){
        return (directDraw||includeCursor||child!=cursor)&&super.drawChild(canvas,child,time);
    }
    private <T extends TextView> T text(T v) {
        v.setSoundEffectsEnabled(false);v.setIncludeFontPadding(false);v.setTextColor(Color.WHITE);
        // Rasterize story glyphs in the game's pixel grid for both the live view and the software wipe snapshot.
        v.setLayerType(View.LAYER_TYPE_SOFTWARE,null);
        textShadow(v,Color.BLACK); addView(v,new LayoutParams(1,1)); return v;
    }
    static void textShadow(TextView view,int color){view.setShadowLayer(2,1,1,color);}
    void style(DialogueStyle value) { style=value==null?DialogueStyle.fallback():value;
        Typeface face=Typeface.DEFAULT;
        if(style.fontWeight>0){
            if(Build.VERSION.SDK_INT>=28)face=Typeface.create(Typeface.DEFAULT,style.fontWeight,false);
            else if(style.fontWeight>=600)face=Typeface.DEFAULT_BOLD;
        }
        body.setTypeface(face);speaker.setTypeface(face);
        textShadow(speaker,style.nameShadeColor);
        updateSpeakerMarkup();
        style.canvas(canvasWidth,canvasHeight);select();
    }
    void canvas(int width,int height){
        int w=Math.max(1,width),h=Math.max(1,height);
        if(w==canvasWidth&&h==canvasHeight)return;
        canvasWidth=w;canvasHeight=h;contentRight=w;style.canvas(w,h);
        window.invalidate();nameWindow.invalidate();requestLayout();
    }
    void variant(int windowType,int fontType,int configType,boolean longPrint,boolean windowVisible){
        boolean skin=windowVisible&&!longPrint;
        if(this.windowType==windowType&&this.fontType==fontType&&this.configType==configType&&this.longPrint==longPrint&&showWindow==skin)return;
        this.windowType=windowType;this.fontType=fontType;this.configType=configType;this.longPrint=longPrint;showWindow=skin;select();
    }
    private void select(){
        int index=DialogueLayout.index(style.counts,windowType,configType,fontType);
        Bitmap windowImage=style.windows[index],nameImage=style.nameWindows[index];
        layout=DialogueLayout.layout(longPrint?style.longLayouts:style.layouts,fontType);
        textScale=-1;
        window.bitmap(windowImage);nameWindow.bitmap(nameImage);
        colors(); requestLayout();
        showWindows();
    }
    void settings(PlayerSettings value) { settings=value;window.invalidate();nameWindow.invalidate();colors(); requestLayout(); }
    void storyBody(boolean value){if(storyBody!=value){storyBody=value;requestLayout();}}
    void passive(boolean value){passive=value;}
    void decorations(DialogueDecorations value,int period,boolean reset){
        if(decorations==value&&!reset)return;
        decorations=value;
        belowWindow.configure(period);aboveWindow.configure(period);
        showWindows();
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){return !passive&&super.dispatchTouchEvent(event);}
    void read(boolean value){wasRead=value;colors();}
    void cursorAssets(GameUiAssets assets){cursor.assets(assets);body.effectColors(assets.printMessage);}
    void cursorVariant(int value){cursor.variant(value);}
    void scriptCursorVariant(int value){cursor.scriptVariant(value);}
    void waiting(boolean visible,boolean automatic,int command,int period){
        cursor.waiting(visible&&(!decorations.cutin||cutinLayer.getVisibility()==VISIBLE),automatic,command,period);
    }
    void contentRight(int value){if(contentRight!=value){contentRight=value;requestLayout();}}
    int bodyX(){return style.bodyX(layout);}
    int bodyY(){return style.bodyY(layout);}
    int bodyWidth(){return style.bodyWidth(layout);}
    int measuredBodyWidth(){return body.getWidth();}
    int bodyHeight(){return style.bodyHeight(layout);}
    private void colors(){
        boolean read=wasRead&&settings.changeReadColor;
        body.setTextColor(read?style.readBodyColor:style.bodyColor);body.markupTextColor(read);
        speaker.setTextColor(read?style.readNameColor:style.speakerColor(speakerName));speaker.markupTextColor(read);
        cutinLayer.colors();
    }
    void speaker(String name,boolean nameWindow) { hasSpeaker=name!=null&&!name.isEmpty();showNameWindow=nameWindow;
        speakerName=hasSpeaker?name:"";
        updateSpeakerMarkup();
        colors();
        showWindows();
        requestLayout();
    }
    private void updateSpeakerMarkup(){
        DialogueMarkup name=style.markup(speakerName);
        speaker.setText(name.text);speaker.markup(name,style.markupStyle);
    }
    private void showWindows(){
        boolean normal=!decorations.cutin;
        window.setVisibility(normal&&showWindow?VISIBLE:GONE);
        nameWindow.setVisibility(normal&&showWindow&&showNameWindow&&style.nameWindowEnabled?VISIBLE:GONE);
        speaker.setVisibility(normal&&!longPrint&&hasSpeaker?VISIBLE:GONE);
        body.setVisibility(normal?VISIBLE:GONE);
        cutinLayer.setVisibility(!normal&&(showWindow||longPrint)?VISIBLE:GONE);
        if(normal)cutinLayer.clear();
    }
    void message(String text,int visible){message(DialogueMarkup.plain(text),visible);}
    void message(DialogueMarkup text,int visible){
        message=text.text;revealed=DialogueLayout.clamp(visible,0,message.length());rendered=null;lastLogicalRow=0;
        body.markup(text,style.markupStyle);body.markupReveal(revealed);body.messageEffect(0,0);
        if(decorations.cutin)cutinLayer.message(text);
        for(int i=0;i<message.length();i++)if(message.charAt(i)=='\n')lastLogicalRow++;
        body.scrollTo(0,0);requestLayout();
    }
    void reveal(int visible,int effect,int progress){
        revealed=DialogueLayout.clamp(visible,0,message.length());
        body.markupReveal(revealed);
        body.messageEffect(effect,progress);
        if(rendered!=null)applyReveal(rendered);
        if(decorations.cutin)cutinLayer.reveal();
    }
    private void applyReveal(Spannable text){
        if(revealed<message.length())text.setSpan(hiddenText,revealed,message.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else text.removeSpan(hiddenText);
    }
    private void layoutMessage(float scale,int origin,int right){
        if(rendered!=null&&textScale==scale&&textOrigin==origin&&textRight==right)return;
        textScale=scale;textOrigin=origin;textRight=right;
        SpannableString result=new SpannableString(message);
        int start=0,row=0;
        while(start<message.length()){
            int end=message.indexOf('\n',start);
            end=end<0?message.length():end+1;
            int indent=DialogueLayout.lineX(layout,row,right)-origin;
            if(indent!=0)result.setSpan(new LeadingMarginSpan.Standard(Math.round(indent*scale)),start,end,Spannable.SPAN_PARAGRAPH);
            start=end;row++;
        }
        applyReveal(result);
        body.setText(result,TextView.BufferType.SPANNABLE);
        rendered=(Spannable)body.getText();
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        float sx=MeasureSpec.getSize(widthSpec)/(float)canvasWidth,sy=MeasureSpec.getSize(heightSpec)/(float)canvasHeight;
        place(window,style.windowX,style.windowY,style.windowWidth,style.windowHeight,sx,sy);
        place(nameWindow,style.nameX,style.nameY,style.nameWidth,style.nameHeight,sx,sy);
        DialogueLayout.Name name=style.nameLayout(fontType);
        int nameX=DialogueLayout.clamp(name.printX(speaker.getText().toString()),-canvasWidth,canvasWidth);
        int nameY=DialogueLayout.clamp(name.y,-canvasHeight,canvasHeight);
        place(speaker,nameX,nameY,canvasWidth-nameX,name.font*2,sx,sy);
        int right=Math.max(1,Math.min(bodyX()+bodyWidth(),contentRight));
        int origin=Math.min(DialogueLayout.lineX(layout,0,right),DialogueLayout.lineX(layout,lastLogicalRow,right));
        place(body,origin,bodyY(),right-origin,bodyHeight(),sx,sy);
        float factor=storyBody?settings.readingSize/100f:1f;
        float originalSize=Math.max(style.original?1:12,layout.font*sx);
        float textSize=originalSize*factor;
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX,textSize);
        body.markupFont(layout.font*factor);
        int markupTop=body.markupTopInset(body.getTextSize());
        if(markupTop>0){
            LayoutParams position=(LayoutParams)body.getLayoutParams();
            position.topMargin-=markupTop;position.height+=markupTop;
        }
        body.setLetterSpacing((float)layout.space/Math.max(1,layout.font));
        android.graphics.Paint.FontMetrics metrics=body.getPaint().getFontMetrics();
        // Keep the game's row positions when shrinking text. Larger text needs room for the extra glyph height.
        float lineStep=Math.max(1,layout.nextY*sy+Math.max(0,textSize-originalSize));
        body.setLineSpacing(lineStep-(metrics.descent-metrics.ascent),1f);
        speaker.setTextSize(TypedValue.COMPLEX_UNIT_PX,Math.max(1,name.font*sx));
        speaker.markupFont(name.font);
        int nameMarkupTop=speaker.markupTopInset(speaker.getTextSize());
        if(nameMarkupTop>0){
            LayoutParams position=(LayoutParams)speaker.getLayoutParams();
            position.topMargin-=nameMarkupTop;position.height+=nameMarkupTop;
        }
        speaker.setLetterSpacing((float)name.space/name.font);
        layoutMessage(sx,origin,right);
        super.onMeasure(widthSpec,heightSpec);
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
        super.onLayout(changed,left,top,right,bottom);
        Layout lines=body.getLayout();
        if(lines==null)return;
        int maxScroll=Math.max(0,lines.getHeight()+body.getTotalPaddingTop()+body.getTotalPaddingBottom()-body.getHeight());
        if(body.getScrollY()>maxScroll)body.scrollTo(body.getScrollX(),maxScroll);
    }
    private void place(android.view.View view,int x,int y,int width,int height,float sx,float sy) {
        LayoutParams p=(LayoutParams)view.getLayoutParams();p.leftMargin=Math.round(x*sx);p.topMargin=Math.round(y*sy);
        p.width=Math.max(1,Math.round(width*sx));p.height=Math.max(1,Math.round(height*sy));
    }
    private final class WindowView extends View {
        private final boolean name;
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect src=new Rect();
        private final RectF dst=new RectF();
        private Bitmap image;

        WindowView(Context context,boolean name){
            super(context);
            this.name=name;
            setSoundEffectsEnabled(false);
        }
        void bitmap(Bitmap value){
            if(image!=value){image=value;invalidate();}
        }
        @Override protected void onDraw(Canvas canvas){
            if(image==null||getWidth()<=0||getHeight()<=0)return;
            int sliceW=name?style.nameWidth:style.windowWidth;
            int sliceH=name?style.nameHeight:style.windowHeight;
            int layers=name?style.nameWindowLayers:style.windowLayers;
            if(sliceW<=0||sliceH<=0)return;
            float sx=getWidth()/(float)sliceW,sy=getHeight()/(float)sliceH;
            for(DialogueLayout.WindowBand band:DialogueLayout.windowBands(sliceW,sliceH,layers,settings.windowOpacity)){
                int w=Math.min(band.width,image.getWidth());
                int h=Math.min(band.height,image.getHeight()-band.srcY);
                if(w<=0||h<=0)continue;
                src.set(0,band.srcY,w,band.srcY+h);
                dst.set(0,0,w*sx,h*sy);
                paint.setAlpha(band.alpha);
                canvas.drawBitmap(image,src,dst,paint);
            }
        }
    }
    private final class PortraitLayer extends View {
        private final int depth;
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dest=new RectF();
        private int period,delay;
        private long cycleStart;
        private boolean moving;

        PortraitLayer(int depth){
            super(DialoguePanel.this.getContext());
            this.depth=depth;
            setVisibility(GONE);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        boolean moving(){return getVisibility()==VISIBLE&&moving;}
        void configure(int period){
            this.period=Math.max(1,period);
            boolean visible=decorations.depth==depth&&decorations.portraits.length>0;
            moving=false;
            setVisibility(visible?VISIBLE:GONE);
            if(visible){
                DialogueDecorations.Portrait primary=decorations.portraits[0];
                delay=waitFrames(primary);
                cycleStart=SystemClock.uptimeMillis();
                moving=DialogueLayout.portraitMoving(0,primary.image!=null,primary.animation.count,primary.animation.active(0),primary.waitMax);
                invalidate();
            }
        }
        private int waitFrames(DialogueDecorations.Portrait portrait){
            return portrait.waitMin==portrait.waitMax?portrait.waitMin:
                ThreadLocalRandom.current().nextInt(portrait.waitMin,portrait.waitMax+1);
        }
        @Override protected void onDraw(Canvas canvas){
            long now=SystemClock.uptimeMillis();
            boolean active=false;
            int saved=canvas.save();
            canvas.scale(getWidth()/(float)canvasWidth,getHeight()/(float)canvasHeight);
            for(int i=0;i<decorations.portraits.length;i++){
                DialogueDecorations.Portrait face=decorations.portraits[i];
                if(face.image==null)continue;
                long frame=i==0?(now-cycleStart)/period-delay:0;
                if(i==0&&face.waitMax>0&&frame>=face.cycle){
                    cycleStart=now;delay=waitFrames(face);frame=-delay;
                }
                SpriteAnimation animation=face.animation;
                dest.set(face.x,face.y,face.x+animation.w,face.y+animation.h);
                long clamped=Math.max(0,frame);
                animation.draw(canvas,face.image,dest,paint,clamped,255);
                active|=DialogueLayout.portraitMoving(i,true,animation.count,animation.active(clamped),face.waitMax);
            }
            canvas.restoreToCount(saved);
            moving=active;
            if(active)postInvalidateOnAnimation();
        }
    }
    private final class CutinLayer extends FrameLayout {
        private final OnClickListener click;
        private final List<DialogueTextView> lines=new ArrayList<>();
        private final List<DialogueMarkup> text=new ArrayList<>();
        private final List<Integer> starts=new ArrayList<>();
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dest=new RectF();

        CutinLayer(OnClickListener click){
            super(DialoguePanel.this.getContext());
            this.click=click;
            setVisibility(GONE);
        }
        void clear(){
            if(lines.isEmpty())return;
            removeAllViews();lines.clear();text.clear();starts.clear();
        }
        void message(DialogueMarkup message){
            clear();
            for(int start=0;start<message.text.length()&&lines.size()<64;){
                int end=message.text.indexOf('\n',start);
                if(end<0)end=message.text.length();
                DialogueMarkup row=message.slice(start,end);
                DialogueTextView line=DialogueTextView.panel(getContext(),row,style.markupStyle,layout.font,style.bodyColor);
                line.setGravity(Gravity.TOP|Gravity.LEFT);line.setMaxLines(1);line.setHorizontallyScrolling(true);
                line.setTextDirection(TEXT_DIRECTION_LTR);line.setTypeface(body.getTypeface());
                line.setSoundEffectsEnabled(false);line.setOnClickListener(click);
                textShadow(line,Color.BLACK);
                lines.add(line);text.add(row);starts.add(start);
                addView(line,new LayoutParams(1,1));
                start=end+1;
            }
            colors();reveal();requestLayout();
        }
        void colors(){
            for(DialogueTextView line:lines){
                line.setTextColor(body.getCurrentTextColor());
                line.markupTextColor(wasRead&&settings.changeReadColor);
            }
        }
        void reveal(){
            for(int i=0;i<lines.size();i++)
                lines.get(i).previewReveal(DialogueLayout.clamp(revealed-starts.get(i),0,text.get(i).text.length()));
            invalidate();
        }
        private DialogueDecorations.Row geometry(int index){
            return index<decorations.rows.length?decorations.rows[index]:null;
        }
        @Override protected void onMeasure(int widthSpec,int heightSpec){
            float sx=MeasureSpec.getSize(widthSpec)/(float)canvasWidth,sy=MeasureSpec.getSize(heightSpec)/(float)canvasHeight;
            float factor=storyBody?settings.readingSize/100f:1f;
            for(int i=0;i<lines.size();i++){
                DialogueDecorations.Row row=geometry(i);
                boolean positioned=row!=null&&row.positioned;
                int x=positioned?row.x:DialogueLayout.lineX(layout,i,canvasWidth);
                int y=positioned?row.y:bodyY()+layout.nextY*i;
                int font=Math.max(1,layout.font+(positioned?row.fontDelta:0));
                DialogueTextView line=lines.get(i);
                float size=Math.max(1,font*sx*factor);
                line.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);line.markupFont(font);
                line.setLetterSpacing((float)layout.space/font);
                int inset=line.markupTopInset(size);
                place(line,x,y,canvasWidth-x,2*font,sx,sy);
                LayoutParams position=(LayoutParams)line.getLayoutParams();
                position.topMargin-=inset;position.height+=inset;
            }
            super.onMeasure(widthSpec,heightSpec);
        }
        @Override protected void dispatchDraw(Canvas canvas){
            long time=getDrawingTime();
            for(int i=0;i<lines.size();i++){
                if(starts.get(i)>revealed)break;
                DialogueDecorations.Row row=geometry(i);
                if(row!=null&&row.type!=0&&row.rows>0)drawWindow(canvas,row,i);
                drawChild(canvas,lines.get(i),time);
            }
        }
        private void drawWindow(Canvas canvas,DialogueDecorations.Row row,int index){
            float sx=getWidth()/(float)canvasWidth,sy=getHeight()/(float)canvasHeight;
            int w,h;
            if(row.type<0){
                if(row.window==null)return;
                w=row.window.getWidth();h=row.window.getHeight();
            }else{
                int count=0;
                for(int i=index;i<Math.min(lines.size(),index+row.rows);i++)count=Math.max(count,text.get(i).glyphCount);
                int font=Math.max(1,layout.font+row.fontDelta);
                w=row.type>1?row.type:(font+1)*count+2*row.padX+row.stepX*(row.rows-1);
                h=font+1+2*row.padY+row.stepY*(row.rows-1);
            }
            if(w<=0||h<=0)return;
            dest.set(row.windowX*sx,row.windowY*sy,(row.windowX+w)*sx,(row.windowY+h)*sy);
            if(row.type<0){
                paint.setAlpha(Math.round(settings.windowOpacity*2.55f));
                canvas.drawBitmap(row.window,null,dest,paint);
            }else{
                paint.setColor(row.color);
                canvas.drawRect(dest,paint);
            }
        }
    }
    private final class MessageCursor extends View {
        private final SpriteAnimation[] clips=new SpriteAnimation[4];
        private final Bitmap[] images=new Bitmap[4];
        private final int[] x=new int[4],y=new int[4],startAlpha=new int[4],endAlpha=new int[4],alphaLoop=new int[4];
        private final boolean[] reverse=new boolean[4];
        private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF dest=new RectF();
        private int period=20,mode;
        private int variant,scriptVariant;
        private GameUiAssets assets;
        private boolean enabled,fixX,fixY,moving;
        private long since;
        MessageCursor(){super(DialoguePanel.this.getContext());setVisibility(GONE);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        boolean moving(){return getVisibility()==VISIBLE&&moving;}
        void assets(GameUiAssets assets){
            this.assets=assets;
            GameUiAssets.Config cfg=assets.messageCursor;
            enabled=assets.game.number("messageCursorCreateFlag",1)!=0;
            fixX=cfg.number("fullFixX",0)!=0;fixY=cfg.number("fullFixY",0)!=0;
            for(int i=0;i<clips.length;i++){
                int n=i+1;
                int w=Math.max(1,Math.min(512,cfg.number("sizeX"+n,cfg.number("sizeX1",32))));
                int h=Math.max(1,Math.min(512,cfg.number("sizeY"+n,cfg.number("sizeY1",32))));
                int pattern=cfg.number("animePattern"+n,cfg.number("animePattern1",1));
                int speed=cfg.number("animeSpeed"+n,cfg.number("animeSpeed1",1));
                int type=cfg.number("animeReverse"+n,cfg.number("animeReverse1",0))!=0?3:1;
                clips[i]=new SpriteAnimation(0,0,w,h,type,pattern,speed,true);
                x[i]=cfg.number("printX"+n,i==0||i==2?0:x[i-1]);y[i]=cfg.number("printY"+n,i==0||i==2?0:y[i-1]);
                startAlpha[i]=percent(cfg.number("percentStart"+n,cfg.number("percentStart1",100)));
                endAlpha[i]=percent(cfg.number("percentEnd"+n,cfg.number("percentEnd1",100)));
                alphaLoop[i]=Math.max(1,Math.min(10000,cfg.number("percentLoop"+n,cfg.number("percentLoop1",1))));
                reverse[i]=cfg.number("percentReverse"+n,cfg.number("percentReverse1",1))!=0;
            }
            loadImages();
        }
        void variant(int value){if(variant!=value){variant=value;loadImages();invalidate();}}
        void scriptVariant(int value){if(scriptVariant!=value){scriptVariant=value;loadImages();invalidate();}}
        private void loadImages(){
            if(assets==null)return;
            GameUiAssets.Config cfg=assets.messageCursor;
            for(int i=0;i<images.length;i++){
                images[i]=assets.image(DialogueLayout.cursorName(cfg.fields(),i,scriptVariant,variant));
            }
        }
        private int percent(int value){return Math.min(100,Math.max(0,value));}
        void waiting(boolean visible,boolean automatic,int command,int frameMs){
            int selected=(automatic?1:0)+(command==0x22220002?2:0);
            boolean show=visible&&enabled&&images[selected]!=null;
            if(show&&(getVisibility()!=VISIBLE||selected!=mode))since=SystemClock.uptimeMillis();
            mode=selected;period=Math.max(1,frameMs);
            SpriteAnimation clip=clips[selected];
            moving=show&&clip!=null&&(clip.active((SystemClock.uptimeMillis()-since)/period)||startAlpha[selected]!=endAlpha[selected]);
            setVisibility(show?VISIBLE:GONE);
            if(show)invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            if(clips[mode]==null){moving=false;return;}
            float sx=getWidth()/(float)canvasWidth,sy=getHeight()/(float)canvasHeight;
            if(sx<=0||sy<=0){moving=false;return;}
            float px=x[mode]*sx,py=y[mode]*sy;
            android.text.Layout lines=body.getLayout();
            if(mode>=2&&decorations.cutin){
                int lastLength=message.codePointCount(message.lastIndexOf('\n')+1,message.length());
                if(!fixX)px+=(layout.x+(long)lastLength*(layout.font+layout.space))*sx;
                if(!fixY)py+=(layout.y+(long)lastLogicalRow*layout.nextY)*sy;
            }else if(mode>=2&&lines!=null&&lines.getLineCount()>0){
                int line=lines.getLineCount()-1;
                if(!fixX)px+=message.endsWith("\n")?DialogueLayout.lineX(layout,lastLogicalRow,textRight)*sx:
                    body.getLeft()+lines.getLineRight(line);
                if(!fixY)py+=body.getTop()+body.getTotalPaddingTop()+lines.getLineTop(line)-body.getScrollY();
            }
            SpriteAnimation clip=clips[mode];dest.set(px,py,px+clip.w*sx,py+clip.h*sy);
            long frame=(SystemClock.uptimeMillis()-since)/period;
            int duration=alphaLoop[mode],n=(int)(frame%(reverse[mode]?2L*duration:duration));
            if(n>duration)n=2*duration-n;
            int alpha=startAlpha[mode]+(endAlpha[mode]-startAlpha[mode])*n/duration;
            clip.draw(canvas,images[mode],dest,paint,frame,alpha*255/100);
            moving=images[mode]!=null&&(clip.active(frame)||startAlpha[mode]!=endAlpha[mode]);
            if(moving)postInvalidateOnAnimation();
        }
    }
}
