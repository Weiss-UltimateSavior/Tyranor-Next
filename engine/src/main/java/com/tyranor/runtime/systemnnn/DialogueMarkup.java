package com.tyranor.runtime.systemnnn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** PC ruby and emphasis commands annotate preceding text on the same script line. */
final class DialogueMarkup {
    static final DialogueMarkup EMPTY=plain("");

    static final class Style {
        final boolean singleByte,enabled;
        final int percent,offsetY,spacing,color;

        Style(Map<String,String> game){
            singleByte=DialogueLayout.number(game,"codeByte",2)==1;
            enabled=DialogueLayout.number(game,"rubiFlag",0)!=0;
            percent=DialogueLayout.clamp(DialogueLayout.number(game,"rubiPercent",40),0,200);
            offsetY=DialogueLayout.clamp(DialogueLayout.number(game,"rubiDeltaY",0),-256,256);
            spacing=DialogueLayout.clamp(DialogueLayout.number(game,"rubiSukima",1),-64,64);
            color=0xff000000|(component(game,"rubiColorR")<<16)|(component(game,"rubiColorG")<<8)|component(game,"rubiColorB");
        }

        private static int component(Map<String,String> game,String key){
            return DialogueLayout.clamp(DialogueLayout.number(game,key,255),0,255);
        }

        DialogueMarkup parse(String source){return DialogueMarkup.parse(source,singleByte,enabled&&percent>0);}
    }

    static final class Annotation {
        final int start,end;
        final String label;
        final boolean emphasis;

        Annotation(int start,int end,String label,boolean emphasis){
            this.start=start;this.end=end;this.label=label;this.emphasis=emphasis;
        }

        int labelOffset(String body,int offset){
            if(offset<=start)return 0;
            if(offset>=end)return label.length();
            int preceding=body.codePointCount(start,offset);
            if(emphasis)return label.offsetByCodePoints(0,preceding);
            int count=body.codePointCount(start,end),labelCount=label.codePointCount(0,label.length());
            return label.offsetByCodePoints(0,(preceding*labelCount+count/2)/count);
        }
    }

    final String text;
    final int glyphCount;
    final List<Annotation> annotations;

    private DialogueMarkup(String text,List<Annotation> annotations){
        this.text=text;this.glyphCount=text.codePointCount(0,text.length());
        this.annotations=Collections.unmodifiableList(annotations);
    }

    static DialogueMarkup plain(String text){return new DialogueMarkup(text,Collections.emptyList());}

    static DialogueMarkup parse(String source,boolean singleByte,boolean showAnnotations){
        if(source.indexOf('#')<0)return plain(source);
        char ruby=singleByte?'[':'\uff3b', emphasis=singleByte?'.':'\u5f37', close=singleByte?']':'\uff3d';
        StringBuilder visible=new StringBuilder(source.length());
        List<Annotation> spans=new ArrayList<>();
        int lineGlyphs=0,lineAnnotations=0;
        for(int i=0;i<source.length();){
            int character=source.codePointAt(i);
            if(character=='#'&&i+1<source.length()){
                char command=source.charAt(i+1);
                if(command==close){i+=2;continue;}
                if(command==ruby||command==emphasis){
                    boolean marks=command==emphasis;
                    int start=i+2,count=0;
                    if(!marks&&start<source.length()){
                        char digit=source.charAt(start++);
                        count=digit>='1'&&digit<='9'?digit-'0':digit-'\uff10';
                    }
                    if(marks||count>=1&&count<=9){
                        int end=start,labelGlyphs=0;
                        while(end<source.length()&&labelGlyphs<100){
                            int next=source.codePointAt(end);
                            if(next==close||next=='\n'||next=='\r')break;
                            end+=Character.charCount(next);labelGlyphs++;
                        }
                        if(labelGlyphs>0&&labelGlyphs<100&&end<source.length()&&source.charAt(end)==close){
                            int baseGlyphs=marks?labelGlyphs:count;
                            if(showAnnotations&&baseGlyphs<=lineGlyphs&&lineAnnotations<256){
                                int baseEnd=visible.length();
                                spans.add(new Annotation(visible.offsetByCodePoints(baseEnd,-baseGlyphs),baseEnd,
                                    source.substring(start,end),marks));
                                lineAnnotations++;
                            }
                            i=end+1;
                            continue;
                        }
                    }
                }
            }
            visible.appendCodePoint(character);
            if(character=='\n'||character=='\r'){lineGlyphs=0;lineAnnotations=0;}
            else lineGlyphs++;
            i+=Character.charCount(character);
        }
        return new DialogueMarkup(visible.toString(),spans);
    }

    DialogueMarkup slice(int start,int end){
        if(start==0&&end==text.length())return this;
        if(annotations.isEmpty())return plain(text.substring(start,end));
        List<Annotation> spans=new ArrayList<>();
        for(Annotation annotation:annotations){
            int first=Math.max(start,annotation.start),last=Math.min(end,annotation.end);
            if(first>=last)continue;
            int labelStart=annotation.labelOffset(text,first),labelEnd=annotation.labelOffset(text,last);
            if(labelStart<labelEnd)spans.add(new Annotation(first-start,last-start,
                annotation.label.substring(labelStart,labelEnd),annotation.emphasis));
        }
        return new DialogueMarkup(text.substring(start,end),spans);
    }
}
