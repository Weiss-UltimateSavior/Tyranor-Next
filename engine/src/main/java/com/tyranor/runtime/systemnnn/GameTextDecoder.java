package com.tyranor.runtime.systemnnn;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** CP932/sjis_ext text, followed by an explicitly enabled UIF Unicode substitution table. */
final class GameTextDecoder {
    private static final int MAX_CONFIG_BYTES=1024*1024;
    private static final String WIDE_KANA="。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソ"+
        "タチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン゛゜";
    private final SjisTunnelDecoder sjis;
    private final SjisTunnelDecoder configuration;
    private final Map<Integer,Integer> substitutions;
    private final Map<Integer,Integer> inverseSubstitutions;

    static GameTextDecoder load(File root) throws IOException {
        File tunnel=GameResourcePaths.file(root,"sjis_ext.bin"),uif=GameResourcePaths.file(root,"uif_config.json");
        return new GameTextDecoder(tunnel==null?new byte[0]:readBounded(tunnel,2*SjisTunnelDecoder.MAX_MAPPINGS),
            uif==null?null:readBounded(uif,MAX_CONFIG_BYTES));
    }
    GameTextDecoder(byte[] tunnel,byte[] uif) throws IOException {
        sjis=new SjisTunnelDecoder(tunnel);
        configuration=SjisTunnelDecoder.legacyConfiguration(tunnel);
        Map<Integer,Integer> table=new HashMap<>();
        if(uif!=null){
            if(uif.length>MAX_CONFIG_BYTES)throw new IOException("UIF text configuration exceeds limit");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(uif)).toString();
            Object parsed=new Json(text).parse();
            if(!(parsed instanceof Map))throw new IOException("UIF configuration must be a JSON object");
            Object section=((Map<?,?>)parsed).get("character_substitution");
            if(section!=null){
                if(!(section instanceof Map))throw new IOException("Invalid UIF character_substitution object");
                Map<?,?> fields=(Map<?,?>)section;Object enabled=fields.get("enable");
                if(enabled!=null && !(enabled instanceof Boolean))throw new IOException("Invalid UIF substitution enable flag");
                if(Boolean.TRUE.equals(enabled)){
                    Object source=fields.get("source_characters"),target=fields.get("target_characters");
                    if(!(source instanceof String)||!(target instanceof String))throw new IOException("Missing UIF character substitution strings");
                    int[] from=codePoints((String)source),to=codePoints((String)target);
                    if(from.length!=to.length)throw new IOException("UIF character substitution lengths differ");
                    for(int i=0;i<from.length;i++)
                        if(table.put(from[i],to[i])!=null)throw new IOException("Duplicate UIF source character");
                }
            }
        }
        substitutions=Collections.unmodifiableMap(table);
        Map<Integer,Integer> inverse=new HashMap<>();
        table.forEach((source,target)->inverse.merge(target,source,(left,right)->-1));
        inverseSubstitutions=Collections.unmodifiableMap(inverse);
    }
    String decode(byte[] input) throws IOException {
        return substitute(sjis.decode(input));
    }
    // The accepted local translations contain unmarked legacy59 configuration
    // text alongside canonical58 SPT text. This is an explicit caller contract,
    // not a claim that XTX itself mandates59, nor a content/language heuristic.
    String decodeConfiguration(byte[] input) throws IOException {
        return substitute(configuration.decode(input));
    }
    byte[] encodeInput(String input,int maxBytes) throws IOException {
        if(input==null||maxBytes<0||maxBytes>SjisTunnelDecoder.MAX_INPUT_BYTES)throw new IOException("无效的输入长度限制");
        ByteArrayOutputStream result=new ByteArrayOutputStream(Math.min(input.length(),maxBytes));
        for(int at=0;at<input.length();){
            int point=input.codePointAt(at);at+=Character.charCount(point);
            if(Character.isISOControl(point)||point>=0xd800&&point<=0xdfff)
                throw new IOException("输入中含有不可用字符");
            Integer source=inverseSubstitutions.get(point);
            if(source!=null&&source<0)throw new IOException("这个字对应多个原版字符，无法确定游戏所需的编码");
            byte[] bytes=source==null||Character.isISOControl(source)?null:sjis.encodeCharacter(source);
            if(bytes==null&&substitutions.getOrDefault(point,point)==point)bytes=sjis.encodeCharacter(point);
            if(bytes==null)throw new IOException("游戏编码无法表示「"+new String(Character.toChars(point))+"」");
            if(bytes.length>maxBytes-result.size())throw new IOException("输入超过 "+maxBytes+" 字节");
            result.write(bytes,0,bytes.length);
        }
        return result.toByteArray();
    }
    /** The original NAMEINPUT expands a typed ASCII or half-width kana key in two-byte font mode. */
    static String fullWidthName(String value){
        StringBuilder converted=new StringBuilder(value.length());
        for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);
            switch(ch){
            case ' ':converted.append('　');break;
            case '"':converted.append('”');break;
            case '\'':converted.append('’');break;
            case '\\':converted.append('￥');break;
            case '`':converted.append('‘');break;
            default:
                if(ch>=0x21&&ch<=0x7e)converted.append((char)(ch+0xfee0));
                else if(ch>=0xff61&&ch<=0xff9f)converted.append(WIDE_KANA.charAt(ch-0xff61));
                else converted.append(ch);
                break;
            }
        }
        return converted.toString();
    }
    private String substitute(String text) {
        if(substitutions.isEmpty())return text;
        StringBuilder result=new StringBuilder(text.length());
        for(int at=0;at<text.length();){
            int point=text.codePointAt(at);at+=Character.charCount(point);
            Integer replacement=substitutions.get(point);result.appendCodePoint(replacement==null?point:replacement);
        }
        return result.toString();
    }
    static byte[] readBounded(File file,int limit) throws IOException {
        if(file.length()>limit)throw new IOException("Text configuration exceeds limit: "+file.getName());
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)){
            byte[] block=new byte[4096];
            for(int n;(n=in.read(block))!=-1;){
                if(n>limit-out.size())throw new IOException("Text configuration exceeds limit: "+file.getName());
                out.write(block,0,n);
            }
        }
        return out.toByteArray();
    }
    private static int[] codePoints(String text) throws IOException {
        int[] result=new int[text.codePointCount(0,text.length())];int n=0;
        for(int at=0;at<text.length();){
            int point=text.codePointAt(at);at+=Character.charCount(point);
            if(point==0 || point>=0xd800 && point<=0xdfff)throw new IOException("Invalid UIF substitution character");
            result[n++]=point;
        }
        return result;
    }

    /** Small bounded JSON reader; unrelated injector options are parsed but never interpreted as text rules. */
    private static final class Json {
        final String text;int at,values;
        Json(String text){this.text=text;at=text.startsWith("\ufeff")?1:0;}
        IOException bad(){return new IOException("Invalid UIF JSON at character "+at);}
        void space(){while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0)at++;}
        boolean take(char c){space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        Object parse() throws IOException {Object value=value(0);space();if(at!=text.length())throw bad();return value;}
        Object value(int depth) throws IOException {
            if(depth>32 || ++values>16384)throw bad();space();if(at==text.length())throw bad();
            char c=text.charAt(at);
            if(c=='"')return string();
            if(take('{')){
                Map<String,Object> map=new HashMap<>();if(take('}'))return map;
                do{space();if(at==text.length()||text.charAt(at)!='"')throw bad();String key=string();
                    if(!take(':')||map.containsKey(key))throw bad();map.put(key,value(depth+1));
                }while(take(','));if(!take('}'))throw bad();return map;
            }
            if(take('[')){
                List<Object> list=new ArrayList<>();if(take(']'))return list;
                do{list.add(value(depth+1));}while(take(','));if(!take(']'))throw bad();return list;
            }
            for(String token:new String[]{"true","false","null"})if(text.startsWith(token,at)){
                at+=token.length();return token.equals("null")?null:Boolean.valueOf(token);
            }
            if(at<text.length()&&text.charAt(at)=='-')at++;
            if(at==text.length())throw bad();
            if(text.charAt(at)=='0')at++;else{if(text.charAt(at)<'1'||text.charAt(at)>'9')throw bad();digits();}
            if(at<text.length()&&text.charAt(at)=='.'){at++;int first=at;digits();if(at==first)throw bad();}
            if(at<text.length()&&(text.charAt(at)=='e'||text.charAt(at)=='E')){
                at++;if(at<text.length()&&(text.charAt(at)=='+'||text.charAt(at)=='-'))at++;
                int first=at;digits();if(at==first)throw bad();
            }
            // Other UIF features may contain numbers; only their JSON type matters here.
            return Integer.valueOf(0);
        }
        void digits(){while(at<text.length()&&text.charAt(at)>='0'&&text.charAt(at)<='9')at++;}
        String string() throws IOException {
            if(!take('"'))throw bad();StringBuilder out=new StringBuilder();
            while(at<text.length()){
                char c=text.charAt(at++);if(c=='"')return out.toString();if(c<32)throw bad();
                if(c=='\\'){
                    if(at==text.length())throw bad();c=text.charAt(at++);
                    switch(c){
                    case '"':case '\\':case '/':break;
                    case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;
                    case 'r':c='\r';break;case 't':c='\t';break;
                    case 'u':
                        if(text.length()-at<4)throw bad();int point=0;
                        for(int i=0;i<4;i++){char hex=text.charAt(at++);int digit=Character.digit(hex,16);if(hex>127||digit<0)throw bad();point=(point<<4)|digit;}
                        c=(char)point;break;
                    default:throw bad();
                    }
                }
                out.append(c);
            }
            throw bad();
        }
    }
}
