package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/** Draws ruby above the existing TextView layout without changing the body's wrapping or weight. */
final class DialogueTextView extends TextView {
    static final class HiddenText extends CharacterStyle implements UpdateAppearance {
        @Override public void updateDrawState(TextPaint paint){paint.setColor(Color.TRANSPARENT);paint.clearShadowLayer();}
    }

    private final Paint annotationPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private DialogueMarkup markup=DialogueMarkup.EMPTY;
    private DialogueMarkup.Style annotationStyle;
    private float canvasFont=24;
    private int revealed;
    private boolean annotationFollowsTextColor;
    private HiddenText previewHidden;

    DialogueTextView(Context context){super(context);}

    static DialogueTextView panel(Context context,DialogueMarkup text,DialogueMarkup.Style style,int font,int color){
        DialogueTextView view=new DialogueTextView(context);
        view.setText(text.text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,font);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        view.markupFont(font);
        view.markup(text,style);
        return view;
    }

    void markup(DialogueMarkup text,DialogueMarkup.Style style){
        markup=text;annotationStyle=style;revealed=text.text.length();requestLayout();invalidate();
    }
    void markupFont(float font){canvasFont=Math.max(1,font);}
    void markupReveal(int end){if(revealed!=end){revealed=end;invalidate();}}
    void markupTextColor(boolean follow){
        if(annotationFollowsTextColor!=follow){annotationFollowsTextColor=follow;invalidate();}
    }

    void previewReveal(int end){
        if(previewHidden==null){
            previewHidden=new HiddenText();
            setText(markup.text,BufferType.SPANNABLE);
        }
        Spannable text=(Spannable)getText();
        if(end<text.length())text.setSpan(previewHidden,end,text.length(),Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        else text.removeSpan(previewHidden);
        markupReveal(end);
    }

    private boolean hasAnnotations(){return annotationStyle!=null&&!markup.annotations.isEmpty();}

    private void sizeAnnotations(float size){
        annotationPaint.setTypeface(getTypeface());
        annotationPaint.setTextSize(Math.max(1,(int)(canvasFont*annotationStyle.percent/100f))*size/canvasFont);
    }

    int markupTopInset(float size){
        if(!hasAnnotations())return 0;
        sizeAnnotations(size);
        return (int)Math.ceil(Math.max(0,annotationPaint.descent()-annotationPaint.ascent()-annotationStyle.offsetY*size/canvasFont));
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int top=markupTopInset(getTextSize());
        if(top!=getPaddingTop())setPadding(getPaddingLeft(),top,getPaddingRight(),getPaddingBottom());
        super.onMeasure(widthSpec,heightSpec);
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(!hasAnnotations()||revealed==0)return;
        Layout lines=getLayout();
        if(lines==null)return;
        float scale=getTextSize()/canvasFont;
        sizeAnnotations(getTextSize());
        annotationPaint.setColor(annotationFollowsTextColor?getCurrentTextColor():annotationStyle.color);
        annotationPaint.setShadowLayer(1,1,1,Color.BLACK);
        int saved=canvas.save();
        canvas.clipRect(getScrollX(),getScrollY(),getScrollX()+getWidth(),getScrollY()+getHeight());
        canvas.translate(getCompoundPaddingLeft(),getTotalPaddingTop());
        for(DialogueMarkup.Annotation span:markup.annotations){
            if(span.start>=revealed||span.start>=lines.getText().length())continue;
            if(span.emphasis)drawEmphasis(canvas,lines,span,scale);
            else drawRuby(canvas,lines,span,scale);
        }
        canvas.restoreToCount(saved);
    }

    private void drawEmphasis(Canvas canvas,Layout lines,DialogueMarkup.Annotation span,float scale){
        int textEnd=Math.min(Math.min(revealed,span.end),lines.getText().length());
        for(int base=span.start,mark=0;base<textEnd;){
            int nextBase=markup.text.offsetByCodePoints(base,1),nextMark=span.label.offsetByCodePoints(mark,1);
            int line=lines.getLineForOffset(base);
            float center=(edge(lines,line,base)+edge(lines,line,nextBase))/2f;
            float width=annotationPaint.measureText(span.label,mark,nextMark);
            int saved=canvas.save();
            clipLine(canvas,lines,line);
            canvas.drawText(span.label,mark,nextMark,center-width/2f,baseline(lines,line,scale),annotationPaint);
            canvas.restoreToCount(saved);
            base=nextBase;mark=nextMark;
        }
    }

    private void drawRuby(Canvas canvas,Layout lines,DialogueMarkup.Annotation span,float scale){
        int end=Math.min(span.end,lines.getText().length());
        for(int start=span.start;start<end&&start<revealed;){
            int line=lines.getLineForOffset(start),stop=Math.min(end,lines.getLineEnd(line));
            if(stop<=start)break;
            int labelStart=span.labelOffset(markup.text,start),labelEnd=span.labelOffset(markup.text,stop);
            if(labelStart<labelEnd){
                float left=lines.getLineLeft(line),right=lines.getWidth();
                float center=(edge(lines,line,start)+edge(lines,line,stop))/2f;
                float gap=annotationStyle.spacing*scale,advance=0;
                int last=span.label.offsetByCodePoints(labelEnd,-1);
                for(int offset=labelStart;offset<last;){
                    int next=span.label.offsetByCodePoints(offset,1);
                    advance+=Math.max(0,annotationPaint.measureText(span.label,offset,next)+gap);
                    offset=next;
                }
                float lastWidth=annotationPaint.measureText(span.label,last,labelEnd);
                float fit=advance==0?1:Math.min(1,Math.max(0,right-left-lastWidth)/advance);
                float width=advance*fit+lastWidth;
                float x=Math.max(left,Math.min(center-width/2f,right-width));
                int saved=canvas.save();
                canvas.clipRect(left,lines.getLineTop(line)-getTotalPaddingTop(),right,lines.getLineBottom(line));
                int visible=span.labelOffset(markup.text,Math.min(stop,revealed));
                float baseline=baseline(lines,line,scale);
                for(int offset=labelStart;offset<visible;){
                    int next=span.label.offsetByCodePoints(offset,1);
                    canvas.drawText(span.label,offset,next,x,baseline,annotationPaint);
                    x+=Math.max(0,annotationPaint.measureText(span.label,offset,next)+gap)*fit;
                    offset=next;
                }
                canvas.restoreToCount(saved);
            }
            start=stop;
        }
    }

    private float baseline(Layout lines,int line,float scale){
        return lines.getLineBaseline(line)+getPaint().ascent()+annotationStyle.offsetY*scale-annotationPaint.descent();
    }

    private void clipLine(Canvas canvas,Layout lines,int line){
        float left=lines.getLineLeft(line),right=lines.getLineRight(line);
        if(revealed<lines.getLineEnd(line)){
            float visible=edge(lines,line,revealed);
            if(lines.getParagraphDirection(line)==Layout.DIR_RIGHT_TO_LEFT)left=Math.max(left,visible);
            else right=Math.min(right,visible);
        }
        canvas.clipRect(left,lines.getLineTop(line)-getTotalPaddingTop(),right,lines.getLineBottom(line));
    }

    private static float edge(Layout lines,int line,int offset){
        if(offset==lines.getLineEnd(line)&&line+1<lines.getLineCount())
            return lines.getParagraphDirection(line)==Layout.DIR_RIGHT_TO_LEFT?lines.getLineLeft(line):lines.getLineRight(line);
        return lines.getPrimaryHorizontal(offset);
    }
}
