package com.tyranor.runtime.systemnnn;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DialogueLayout {
    static final int MAX_VARIANTS=256,MAX_LAYOUTS=64;
    static final class Counts {
        final int config,script,font;
        Counts(int config,int script,int font){this.config=config;this.script=script;this.font=font;}
        int size(){return config*script*font;}
    }
    static final class Text {
        final int font,x,y,nextX,nextY,space;
        Text(int font,int x,int y,int nextX,int nextY,int space){
            this.font=font;this.x=x;this.y=y;this.nextX=nextX;this.nextY=nextY;this.space=space;
        }
    }
    static final class Name {
        final int x,y,font,space,columns,alignment,cellWidth;
        Name(int x,int y,int font,int space,int columns,int alignment,boolean singleByte){
            this.x=x;this.y=y;this.font=font;this.space=space;this.columns=columns;this.alignment=alignment;
            cellWidth=singleByte?font/2:font;
        }
        int printX(String name){
            if(alignment==0)return x;
            int length=name.codePointCount(0,name.length());
            long shift=(long)Math.max(0,columns-length)*(cellWidth+space)/(alignment==1?2:1);
            return (int)Math.max(Integer.MIN_VALUE,Math.min(Integer.MAX_VALUE,x+shift));
        }
    }
    static final class Message {
        final String name,body;
        final boolean nameWindow;
        Message(String name,String body,boolean nameWindow){this.name=name;this.body=body;this.nameWindow=nameWindow;}
    }
    static int clamp(int value,int low,int high){return Math.max(low,Math.min(value,high));}
    static String cursorName(Map<String,String> config,int kind,int scriptVariant,int configVariant){
        String key="filenameCursor"+(kind+1);
        String base=value(config,key,value(config,"filenameCursor1","mes_cur"));
        int scripts=clamp(number(config,"cursorNumberScript",1),1,64);
        int settings=clamp(number(config,"cursorNumberConfig",1),1,64);
        if(scripts==1&&settings==1)return base;
        int script=clamp(scriptVariant,0,scripts-1)+1,setting=clamp(configVariant,0,settings-1)+1;
        boolean both=scripts>1&&settings>1;
        // PC variants inherit the first script/config cell after its override is read.
        base=value(config,key+(both?"_1_1":"_1"),base);
        return value(config,key+"_"+(both?script+"_"+setting:Integer.toString(scripts>1?script:setting)),base);
    }
    static String value(Map<String,String> values,String key,String fallback){
        String v=values.get(key.toLowerCase(Locale.ROOT));return v==null||v.isEmpty()?fallback:v;
    }
    static int number(Map<String,String> values,String key,int fallback){
        String v=value(values,key,"").trim();int end=0;
        while(end<v.length()&&!Character.isWhitespace(v.charAt(end)))end++;
        try{return Integer.parseInt(v.substring(0,end));}catch(NumberFormatException e){return fallback;}
    }
    static Counts counts(Map<String,String> config){
        int c=clamp(number(config,"windowNumberConfig",1),1,16);
        int s=clamp(number(config,"windowNumberScript",1),1,16);
        int f=clamp(number(config,"windowNumberFontSizeType",1),1,MAX_VARIANTS/(c*s));
        return new Counts(c,s,f);
    }
    static int index(Counts n,int script,int config,int font){
        int j=clamp(script,0,n.script-1),i=clamp(config,0,n.config-1),k=clamp(font,0,n.font-1);
        return k*n.script*n.config+j*n.config+i;
    }
    static String key(String prefix,Counts n,int script,int config,int font){
        String suffix=n.script>1&&n.config>1?(script+1)+"_"+(config+1):
            Integer.toString((n.script<=1?config:script)+1);
        return prefix+suffix+(font>0?"_Font"+font:"");
    }
    static String[] names(Map<String,String> values,String prefix,String defaultName,Counts n){
        String[] result=new String[n.size()];String base=value(values,prefix,defaultName);
        for(int k=0;k<n.font;k++)for(int j=0;j<n.script;j++)for(int i=0;i<n.config;i++)
            result[index(n,j,i,k)]=value(values,key(prefix,n,j,i,k),base);
        return result;
    }
    static Text[] layouts(Map<String,String> values){
        int count=clamp(number(values,"fontSizeTypeNumber",0),0,MAX_LAYOUTS-1)+1;
        int font=Math.min(72,number(values,"fontSize",24)),x=number(values,"printX",100),y=number(values,"printY",100);
        int nx=number(values,"nextX",0),ny=number(values,"nextY",font+6),space=number(values,"space",1);
        Text[] result=new Text[count];result[0]=new Text(font,x,y,nx,ny,space);
        for(int i=1;i<count;i++)result[i]=new Text(
            Math.min(72,number(values,"fontSizeByFontSizeType"+i,font)),number(values,"printXByFontSizeType"+i,x),
            number(values,"printYByFontSizeType"+i,y),number(values,"nextXByFontSizeType"+i,nx),
            number(values,"nextYByFontSizeType"+i,ny),number(values,"spaceByFontSizeType"+i,space));
        return result;
    }
    static Text layout(Text[] layouts,int font){return layouts[clamp(font,0,layouts.length-1)];}
    static Name[] nameLayouts(Map<String,String> values,Text[] normal,boolean singleByte){
        int x=number(values,"nameX",normal[0].x-normal[0].nextX),y=number(values,"nameY",normal[0].y-normal[0].nextY);
        int font=clamp(number(values,"nameFontSize",20),1,72),space=clamp(number(values,"nameSpace",1),-1024,1024);
        int columns=clamp(number(values,"nameLengthMax",5),0,4096),alignment=number(values,"nameCenterFlag",1);
        Name[] result=new Name[normal.length];
        // PC caches the type-zero default spacing before reading nameSpace.
        result[0]=new Name(x,y,font,normal.length>1?1:space,columns,alignment,singleByte);
        for(int i=1;i<result.length;i++)result[i]=new Name(number(values,"nameXByFontSizeType"+i,x),
            number(values,"nameYByFontSizeType"+i,y),font,clamp(number(values,"nameSpaceByFontSizeType"+i,space),-1024,1024),
            columns,alignment,singleByte);
        return result;
    }
    static Message split(String text,boolean print,boolean showName,boolean automatic,Set<String> names){
        if(!print)return new Message("",text,false);
        int end=text.indexOf('\n');
        String first=end<0?text:text.substring(0,end);
        boolean recognized=automatic&&names.contains(first);
        if(!showName||automatic&&!recognized)return new Message("",text,recognized);
        return new Message(first,end<0?"":text.substring(end+1),recognized);
    }
    static boolean usesNameTable(Map<String,String> values){
        return number(values,"nameColorChangeFlag",1)!=0||number(values,"autoNameWindowFlag",0)!=0;
    }
    static Text[] longLayouts(Map<String,String> values,Text[] normal){
        int x=number(values,"LPrintX",100),y=number(values,"LPrintY",100);
        int nx=number(values,"LNextX",normal[0].nextX),ny=number(values,"LNextY",normal[0].nextY);
        Text[] result=new Text[normal.length];
        for(int i=0;i<result.length;i++)result[i]=new Text(normal[i].font,x,y,nx,ny,normal[i].space);
        return result;
    }
    static int lineX(Text layout,int row,int right){
        return (int)Math.max(0,Math.min(right-1L,layout.x+(long)row*layout.nextX));
    }
}
