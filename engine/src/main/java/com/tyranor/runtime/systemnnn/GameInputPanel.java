package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import java.io.IOException;
import java.util.Map;

/** Script NAMEINPUT and KANJIINPUT use the game's canvas and an Android IME. */
final class GameInputPanel extends GameUiPanel {
    static final int NAME=29,KANJI=46;
    interface Result {void finish(boolean accepted,int elapsedFrames,byte[][] values,Bitmap previous);}
    private static final byte[] EMPTY=new byte[0];
    private static final String[] TAG={"sei","mei"};
    private static final String[] NAME_LABEL={"姓氏","名字"};
    private static final String[] DIALOG={"ta_sei_dialog","ta_mei_dialog"};
    private final GameUiAssets.Config config;
    private final Map<String,Bitmap> images;
    private final GameTextDecoder codec;
    private final Result result;
    private final boolean name,wide,allowEmpty,allowReturn;
    private final int[] limits,order;
    private final int kanjiTarget;
    private final byte[][] values,emptyNames;
    private final boolean[] confirmed;
    private int step;
    private EditText editor;
    private GameButton ok,cancel;
    private String shown="";
    private boolean foreground=true,closing,firstActiveFrame;
    private long activeAt=-1,activeMillis;

    GameInputPanel(Context context,GameUiAssets assets,GameUiAssets.Config config,Map<String,Bitmap> images,
                   GameTextDecoder codec,int mode,int width,int height,int[] arguments,int[] byteLimits,
                   byte[][] text,Runnable click,Result result){
        super(context,assets,config.value("filenameBG",""),click);
        if(mode!=NAME&&mode!=KANJI)throw new IllegalArgumentException("Unsupported input mode "+mode);
        this.config=config;this.images=images;this.codec=codec;this.result=result;
        name=mode==NAME;wide=assets.game.number("codeByte",2)!=1;
        allowEmpty=name&&config.number("nullNameOk",0)!=0;
        allowReturn=name&&config.number("returnOk",1)!=0;
        limits=byteLimits.clone();
        if(limits.length!=(name?2:1))throw new IllegalArgumentException("Invalid input field limits");
        if(name){
            boolean surname=config.number("seiFlag",1)!=0,given=config.number("meiFlag",1)!=0;
            if(surname&&given)order=config.number("seimeiReverse",0)!=0?new int[]{1,0}:new int[]{0,1};
            else order=new int[]{given?1:0};
            values=new byte[][]{field(text,0),field(text,1)};
            emptyNames=new byte[][]{field(text,2),field(text,3)};
        }else{
            order=new int[]{0};
            values=new byte[][]{field(text,0)};
            emptyNames=new byte[][]{EMPTY};
        }
        kanjiTarget=arguments.length>1?arguments[1]:0;
        confirmed=new boolean[values.length];
        setContentDescription(name?"姓名输入":"文字输入");
        setFocusableInTouchMode(true);
        canvas(width,height);
        opening(config,false);
        uiBackground(config,mode,1);
        showStep(false);
    }

    private static byte[] field(byte[][] fields,int index){
        return index<fields.length&&fields[index]!=null?fields[index].clone():EMPTY;
    }
    @Override Bitmap sprite(String key){
        Bitmap bitmap=key==null?null:images.get(GameUiAssets.systemImageKey(key));
        return bitmap==null?super.sprite(key):bitmap;
    }
    private int size(String key,int fallback){return Math.max(1,Math.min(4096,config.number(key,fallback)));}
    private int font(String key,int fallback){return Math.max(8,Math.min(128,config.number(key,fallback)));}
    private int color(String key,int fallback){
        return Color.rgb(component(key+"R",Color.red(fallback)),component(key+"G",Color.green(fallback)),
            component(key+"B",Color.blue(fallback)));
    }
    private int component(String key,int fallback){return Math.max(0,Math.min(255,config.number(key,fallback)));}
    private byte[] displayBytes(int index){
        return confirmed[index]&&values[index].length==0?emptyNames[index]:values[index];
    }
    private String displayText(int index){
        try{return codec.decode(displayBytes(index));}
        catch(IOException invalid){return "";}
    }
    private int nameWidth(int index,int font,int gap){
        return (font+gap)*Math.min(128,size(TAG[index]+"Max",config.number("max",4)));
    }
    private static int textGravity(int align){
        return Gravity.CENTER_VERTICAL|(align==1?Gravity.CENTER_HORIZONTAL:align==2?Gravity.RIGHT:Gravity.LEFT);
    }

    private void showStep(boolean focused){
        clearControls();
        int index=order[step];
        if(name)showName(index);else showKanji();
        refreshButtons();
        if(focused)editor.requestFocus();else requestFocus();
    }
    private void showName(int index){
        String tag=TAG[index];
        int x=config.number("dialogPrintX",300),y=config.number("dialogPrintY",250);
        int w=size("dialogSizeX",200),h=size("dialogSizeY",100);
        Bitmap dialog=sprite(config.value(tag+"DialogFileName",DIALOG[index]));
        if(dialog!=null)dialog(dialog,x,y,w,h);
        int other=1-index;
        String otherTag=TAG[other];
        if(config.number(otherTag+"DontPrint",other)==0){
            int font=font(otherTag+"FontSize",font("seiFontSize",24));
            int gap=Math.min(font,Math.max(0,config.number(otherTag+"FontSukima",config.number("seiFontSukima",1))));
            TextView otherText=text(displayText(other),font,color(otherTag+"FixColor",color("seiFixColor",Color.WHITE)));
            otherText.setSingleLine();otherText.setGravity(textGravity(config.number(otherTag+"AlignMode",0)));
            otherText.setLetterSpacing(gap/(float)font);
            put(otherText,x+config.number(otherTag+"PrintX",0),y+config.number(otherTag+"PrintY",0),
                nameWidth(other,font,gap),font+6);
        }
        int font=font(tag+"FontSize",font("fontSize",24));
        int gap=Math.min(font,Math.max(0,config.number(tag+"SukimaSize",config.number("sukimaSize",1))));
        addEditor(index,NAME_LABEL[index],font,gap,color(tag+"Color",color("color",Color.WHITE)),
            textGravity(config.number(tag+"AlignMode",config.number("alignMode",0))),
            x+config.number(tag+"PrintX",0),y+config.number(tag+"PrintY",0),
            nameWidth(index,font,gap),font+8);
        int buttonY=Math.min(canvasHeight-50,y+h+8);
        ok=button(config,"ok","ok",step+1<order.length?"下一步":"确定",this::confirm,
            x,buttonY,96,44);
        cancel=button(config,"cancel","cancel",step==0?"取消":"上一步",this::goBack,
            x+104,buttonY,96,44);
    }
    private void dialog(Bitmap bitmap,int x,int y,int w,int h){
        Rect source=new Rect(0,0,Math.min(w,bitmap.getWidth()),Math.min(h,bitmap.getHeight()));
        Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
        View art=new View(getContext()){
            @Override protected void onDraw(Canvas canvas){
                canvas.drawBitmap(bitmap,source,new RectF(0,0,getWidth(),getHeight()),paint);
            }
        };
        put(art,x,y,source.width(),source.height());
    }
    private void showKanji(){
        int x=config.number("printX",300),y=config.number("printY",50);
        int font=font("fontSize",24);
        int w=Math.max(font+8,Math.min(canvasWidth-Math.max(0,x),(font+1)*Math.max(4,limits[0]/(wide?2:1))));
        String label=kanjiTarget==2?NAME_LABEL[0]:kanjiTarget==3?NAME_LABEL[1]:"文字";
        addEditor(0,label,font,1,color("kakuteiFontColor",color("fontColor",Color.WHITE)),Gravity.LEFT|Gravity.CENTER_VERTICAL,
            x,y,w,font+8);
        ok=button(config,"ok","ok","确定",this::confirm,x,Math.min(canvasHeight-48,y+font+24),100,44);
        cancel=null;
    }
    private void addEditor(int index,String label,int font,int gap,int color,int gravity,int x,int y,int w,int h){
        editor=new EditText(getContext());
        editor.setId(View.generateViewId());
        editor.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        editor.setSaveEnabled(false);
        editor.setSingleLine(true);editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        editor.setPadding(0,0,0,0);editor.setIncludeFontPadding(false);
        editor.setTextSize(TypedValue.COMPLEX_UNIT_PX,font);
        editor.setTextColor(color);editor.setHintTextColor(0xffadb8c6);
        editor.setBackgroundColor(0x28000000);editor.setGravity(gravity);editor.setLetterSpacing(gap/(float)font);
        editor.setContentDescription(label);editor.setHint(label);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI|EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|
            (step+1<order.length?EditorInfo.IME_ACTION_NEXT:EditorInfo.IME_ACTION_DONE));
        shown=displayText(index);
        editor.setText(shown);editor.setSelection(editor.length());
        editor.setOnEditorActionListener((view,action,event)->{
            if(action!=EditorInfo.IME_ACTION_NEXT&&action!=EditorInfo.IME_ACTION_DONE&&
               (event==null||event.getKeyCode()!=KeyEvent.KEYCODE_ENTER||event.getAction()!=KeyEvent.ACTION_UP))return false;
            if(view==editor&&canEdit()&&ok!=null&&ok.isEnabled())ok.performClick();
            return true;
        });
        EditText currentEditor=editor;
        editor.addTextChangedListener(new TextWatcher(){
            @Override public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            @Override public void onTextChanged(CharSequence value,int start,int before,int count){}
            @Override public void afterTextChanged(Editable value){
                if(editor==currentEditor){editor.setError(null);refreshButtons();}
            }
        });
        put(editor,x,y,w,h);
    }
    private void refreshButtons(){
        if(ok!=null)ok.setEnabled(!closing&&(editor.length()>0||allowEmpty));
        if(cancel!=null)cancel.setEnabled(!closing&&(step>0||allowReturn));
    }
    private boolean canEdit(){return foreground&&!closing&&activeFrameReady()&&!openingRunning();}
    private void confirm(){
        if(!canEdit())return;
        Editable input=editor.getText();
        if(BaseInputConnection.getComposingSpanStart(input)>=0){
            editor.setError("请先在键盘中确认候选文字");
            return;
        }
        int index=order[step];
        try{
            String current=input.toString();
            byte[] raw;
            if(name&&current.equals(shown))raw=values[index];
            else{
                String normalized=name&&wide?GameTextDecoder.fullWidthName(current):current;
                raw=codec.encodeInput(normalized,limits[index]);
                checkCharacters(raw);
                if(!normalized.equals(current)){editor.setText(normalized);editor.setSelection(editor.length());}
            }
            if(raw.length==0&&!allowEmpty){editor.setError("请输入文字");return;}
            if(raw.length>limits[index]){editor.setError("输入超过 "+limits[index]+" 字节");return;}
            values[index]=raw;confirmed[index]=true;
        }catch(IOException invalid){editor.setError(invalid.getMessage());return;}
        if(step+1==order.length)finish(true);
        else {boolean focused=editor.hasFocus();step++;showStep(focused);}
    }
    private void checkCharacters(byte[] raw) throws IOException {
        if(name&&!wide){
            for(byte value:raw)if((value&255)>0x7e||value=='#')
                throw new IOException("此姓名输入框只接受半角文字，且不能输入 #");
        }else if(wide){
            int maxLead=name?0xfc:0xef;
            for(int i=0;i<raw.length;i+=2){
                int lead=raw[i]&255;
                if(i+1>=raw.length||!(lead>=0x81&&lead<=0x9f||lead>=0xe0&&lead<=maxLead))
                    throw new IOException("此输入框只接受游戏支持的全角字符");
            }
        }
    }
    private void goBack(){
        if(!canEdit())return;
        if(step>0){boolean focused=editor.hasFocus();step--;showStep(focused);}
        else if(allowReturn)finish(false);
    }
    private void hideKeyboard(){
        InputMethodManager ime=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(ime!=null&&editor!=null)ime.hideSoftInputFromWindow(editor.getWindowToken(),0);
    }
    @Override boolean backOverlay(){
        if(closing)return true;
        WindowInsets insets=getRootWindowInsets();
        if(Build.VERSION.SDK_INT>=30&&insets!=null&&insets.isVisible(WindowInsets.Type.ime()))hideKeyboard();
        else if(cancel!=null&&cancel.isEnabled())cancel.performClick();
        return true;
    }
    @Override public void draw(Canvas canvas){
        super.draw(canvas);
        if(activeFrameReady()){firstActiveFrame=true;startClock();}
    }
    @Override void uiActive(boolean active){
        foreground=active;
        if(!active)stopClock();
        super.uiActive(active);
        if(active&&firstActiveFrame)startClock();
    }
    private void startClock(){
        if(foreground&&!closing&&activeAt<0)activeAt=SystemClock.uptimeMillis();
    }
    private void stopClock(){
        if(activeAt>=0){activeMillis+=SystemClock.uptimeMillis()-activeAt;activeAt=-1;}
    }
    private void finish(boolean accepted){
        closing=true;stopClock();hideKeyboard();refreshButtons();
        int elapsed=(int)Math.min(9999,1+activeMillis/frameMs);
        byte[][] reply=accepted?values.clone():new byte[0][];
        leave(config,false,previous->result.finish(accepted,elapsed,reply,previous));
    }
    @Override void cancelLeave(){
        super.cancelLeave();closing=false;refreshButtons();startClock();
        editor.setError("游戏拒绝了这次输入");
    }
    @Override protected void onDetachedFromWindow(){
        hideKeyboard();
        super.onDetachedFromWindow();
    }
}
