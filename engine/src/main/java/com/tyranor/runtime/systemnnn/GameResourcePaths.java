package com.tyranor.runtime.systemnnn;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Physical Android paths and the CP932 names referenced by the original engine. */
final class GameResourcePaths {
    private static final Charset CP932=Charset.forName("windows-31j");

    // JNI calls this only for a physical directory entry, never a game root or script string.
    static byte[] encodeFilename(byte[] utf8){
        try {
            ByteBuffer result=CP932.newEncoder().encode(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(utf8)));
            byte[] encoded=new byte[result.remaining()];
            result.get(encoded);
            return encoded;
        } catch (CharacterCodingException e) { return null; }
    }

    static String decodeFilename(byte[] raw){
        try {return CP932.newDecoder().decode(ByteBuffer.wrap(raw)).toString();}
        catch (CharacterCodingException e) {return null;}
    }

    static String displayScript(byte[] raw){
        String name=decodeFilename(raw);
        return name!=null?name:new String(raw,StandardCharsets.UTF_8);
    }

    static File file(File root,String name){return child(root,name,false);}
    static File file(File root,String folder,String name){return child(child(root,folder,true),name,false);}
    static File directory(File root,String name){return child(root,name,true);}

    private static File child(File parent,String name,boolean directory){
        if(parent==null||name==null||name.isEmpty()||name.equals(".")||name.equals("..")||
            name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf('\0')>=0)return null;
        File direct=new File(parent,name);
        if(directory?direct.isDirectory():direct.isFile())return direct;
        File[] entries=parent.listFiles((dir,entry)->sameName(name,entry));
        File found=null;
        if(entries!=null)for(File entry:entries){
            if((directory?entry.isDirectory():entry.isFile())&&(found==null||entry.getName().compareTo(found.getName())<0))found=entry;
        }
        return found;
    }

    private static boolean sameName(String first,String second){
        if(first.length()!=second.length())return false;
        for(int i=0;i<first.length();i++)if(asciiLower(first.charAt(i))!=asciiLower(second.charAt(i)))return false;
        return true;
    }

    private static char asciiLower(char c){return c>='A'&&c<='Z'?(char)(c+'a'-'A'):c;}
}
