package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.util.TypedValue;
import android.text.method.ScrollingMovementMethod;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Layout;
import android.text.style.LeadingMarginSpan;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/** Message and name windows use the game's logical canvas. */
final class DialoguePanel extends FrameLayout {
    final DialogueTextView speaker,body;
    private final ImageView window,nameWindow;
    private final MessageCursor cursor;
    private boolean drawingMessagePlane;
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
        window=image(); nameWindow=image();
        speaker=text(new DialogueTextView(context));speaker.setGravity(Gravity.TOP|Gravity.LEFT);speaker.setTextDirection(TEXT_DIRECTION_LTR);speaker.setSingleLine(true);
        body=text(new DialogueTextView(context));body.setGravity(Gravity.TOP|Gravity.LEFT);body.setTextDirection(TEXT_DIRECTION_LTR);
        body.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);body.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        body.setAccessibilityDelegate(new AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.setText(message.substring(0,revealed));
            }
        });
        body.setMovementMethod(new ScrollingMovementMethod());body.setVerticalScrollBarEnabled(true);
        window.setOnClickListener(click); nameWindow.setOnClickListener(click);
        speaker.setOnClickListener(click); body.setOnClickListener(click);
        cursor=new MessageCursor();addView(cursor,new LayoutParams(-1,-1));
    }
    private ImageView image() { ImageView v=new ImageView(getContext());v.setSoundEffectsEnabled(false);v.setScaleType(ImageView.ScaleType.FIT_XY); addView(v,new LayoutParams(1,1)); return v; }
    void drawMessagePlane(Canvas canvas){
        if(getVisibility()!=VISIBLE)return;
        drawingMessagePlane=true;
        try{draw(canvas);}finally{drawingMessagePlane=false;}
    }
    void drawMessageCursor(Canvas canvas){
        if(getVisibility()==VISIBLE&&cursor.getVisibility()==VISIBLE)cursor.draw(canvas);
    }
    boolean isMessageCursor(View view){return view==cursor;}
    @Override protected boolean drawChild(Canvas canvas,View child,long time){
        return (!drawingMessagePlane||child!=cursor)&&super.drawChild(canvas,child,time);
    }
    private <T extends TextView> T text(T v) {
        v.setSoundEffectsEnabled(false);v.setIncludeFontPadding(false);v.setTextColor(Color.WHITE);
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
        canvasWidth=w;canvasHeight=h;contentRight=w;style.canvas(w,h);requestLayout();
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
        window.setImageBitmap(windowImage);nameWindow.setImageBitmap(nameImage);
        if(windowImage==null)window.setBackground(rounded(0xdc1b202b,12));else window.setBackgroundColor(Color.TRANSPARENT);
        if(nameImage==null)nameWindow.setBackground(rounded(0xe3242b38,8));else nameWindow.setBackgroundColor(Color.TRANSPARENT);
        colors(); requestLayout();
        showWindows();
    }
    void settings(PlayerSettings value) { settings=value;window.setAlpha(value.windowOpacity/100f);nameWindow.setAlpha(value.windowOpacity/100f);colors(); requestLayout(); }
    void storyBody(boolean value){if(storyBody!=value){storyBody=value;requestLayout();}}
    void passive(boolean value){passive=value;}
    @Override public boolean dispatchTouchEvent(MotionEvent event){return !passive&&super.dispatchTouchEvent(event);}
    void read(boolean value){wasRead=value;colors();}
    void cursorAssets(GameUiAssets assets){cursor.assets(assets);}
    void cursorVariant(int value){cursor.variant(value);}
    void scriptCursorVariant(int value){cursor.scriptVariant(value);}
    void waiting(boolean visible,boolean automatic,int command,int period){
        cursor.waiting(visible,automatic,command,period);
    }
    void contentRight(int value){if(contentRight!=value){contentRight=value;requestLayout();}}
    int bodyX(){return style.bodyX(layout);}
    int bodyY(){return style.bodyY(layout);}
    int bodyWidth(){return style.bodyWidth(layout);}
    int bodyHeight(){return style.bodyHeight(layout);}
    private void colors(){
        boolean read=wasRead&&settings.changeReadColor;
        body.setTextColor(read?style.readBodyColor:style.bodyColor);body.markupTextColor(read);
        speaker.setTextColor(read?style.readNameColor:style.speakerColor(speakerName));speaker.markupTextColor(read);
    }
    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable background=new GradientDrawable();background.setColor(color);
        background.setCornerRadius(radius*getResources().getDisplayMetrics().density);return background;
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
        window.setVisibility(showWindow?VISIBLE:GONE);
        nameWindow.setVisibility(showWindow&&showNameWindow&&style.nameWindowEnabled?VISIBLE:GONE);
        speaker.setVisibility(!longPrint&&hasSpeaker?VISIBLE:GONE);
    }
    void message(String text,int visible){message(DialogueMarkup.plain(text),visible);}
    void message(DialogueMarkup text,int visible){
        message=text.text;revealed=DialogueLayout.clamp(visible,0,message.length());rendered=null;lastLogicalRow=0;
        body.markup(text,style.markupStyle);body.markupReveal(revealed);
        for(int i=0;i<message.length();i++)if(message.charAt(i)=='\n')lastLogicalRow++;
        body.scrollTo(0,0);requestLayout();
    }
    void reveal(int visible){
        revealed=DialogueLayout.clamp(visible,0,message.length());
        body.markupReveal(revealed);
        if(rendered!=null)applyReveal(rendered);
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
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX,Math.max(style.original?1:12,layout.font*sx)*factor);
        body.markupFont(layout.font*factor);
        int markupTop=body.markupTopInset(body.getTextSize());
        if(markupTop>0){
            LayoutParams position=(LayoutParams)body.getLayoutParams();
            position.topMargin-=markupTop;position.height+=markupTop;
        }
        body.setLetterSpacing((float)layout.space/Math.max(1,layout.font));
        android.graphics.Paint.FontMetrics metrics=body.getPaint().getFontMetrics();
        body.setLineSpacing(Math.max(1,layout.nextY*sy*factor)-(metrics.descent-metrics.ascent),1f);
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
        private boolean enabled,fixX,fixY;
        private long since;
        MessageCursor(){super(DialoguePanel.this.getContext());setVisibility(GONE);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
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
            mode=selected;period=Math.max(1,frameMs);setVisibility(show?VISIBLE:GONE);
            if(show)invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            if(clips[mode]==null)return;
            float sx=getWidth()/(float)canvasWidth,sy=getHeight()/(float)canvasHeight;
            if(sx<=0||sy<=0)return;
            float px=x[mode]*sx,py=y[mode]*sy;
            android.text.Layout lines=body.getLayout();
            if(mode>=2&&lines!=null&&lines.getLineCount()>0){
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
            if(clip.active(frame)||startAlpha[mode]!=endAlpha[mode])postInvalidateOnAnimation();
        }
    }
}
