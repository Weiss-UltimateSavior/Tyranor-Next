package com.tyranor.runtime.systemnnn;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Effective save format stored in the game's Emberfall configuration file. */
public final class SaveLayoutConfig {
    public enum SystemSaveSource { PENDING, EXECUTABLE, TEMPLATE, GAME_FILES }
    private static final SystemSaveSource[] SYSTEM_SAVE_SOURCES=SystemSaveSource.values();
    public final String path;
    public final int layerRecords, messageWidth, statusBytes, systemSaveVersion;
    public final boolean executableLayout, okikae;
    public final boolean executableMessageWidth, executableStatusBytes, executableOkikae;
    public final SystemSaveSource systemSaveSource;
    public final int extensionStartCode; // -1 unresolved, 0 none; positive values are not an enum.
    public final int extensionEnabled; // -1 unknown in older configurations.
    public final List<Integer> extensionSizes; // -1 is an unknown payload length.
    private final boolean hasExtensionDetails;

    private SaveLayoutConfig(String path,int[] fields){
        this.path=path;
        layerRecords=fields[0];
        messageWidth=fields[1];
        statusBytes=fields[2];
        systemSaveVersion=fields[3];
        executableLayout=fields[4]!=0;
        okikae=fields[5]!=0;
        systemSaveSource=SYSTEM_SAVE_SOURCES[fields[6]];
        executableMessageWidth=fields[7]!=0;
        executableStatusBytes=fields[8]!=0;
        executableOkikae=fields[9]!=0;
        extensionStartCode=fields.length>10?fields[10]:-1;
        hasExtensionDetails=fields.length>=12;
        extensionEnabled=hasExtensionDetails?fields[11]:(okikae||extensionStartCode>0?1:extensionStartCode);
        if(fields.length<=12)extensionSizes=Collections.emptyList();
        else{
            List<Integer> sizes=new ArrayList<>(fields.length-12);
            for(int i=12;i<fields.length;i++)sizes.add(fields[i]);
            extensionSizes=Collections.unmodifiableList(sizes);
        }
    }

    static SaveLayoutConfig open(File root,boolean regenerate) throws IOException {
        Object[] result=NativeGame.saveLayoutConfig(root.getPath().getBytes(StandardCharsets.UTF_8),regenerate);
        if(result==null){
            if(regenerate)throw new IOException("Unable to write emberfall-system.cfg");
            return null;
        }
        if(result.length!=2 || !(result[0] instanceof byte[]) || !(result[1] instanceof int[]))
            throw new IOException("Invalid Emberfall layout response");
        return fromFields(new String((byte[])result[0],StandardCharsets.UTF_8),(int[])result[1]);
    }

    static SaveLayoutConfig fromFields(String path,int[] fields) throws IOException {
        if(path==null || path.isEmpty() || fields==null || fields.length<10 || fields.length>12+256 ||
           (fields.length>=11&&(fields[10]<-1||fields[10]>Integer.MAX_VALUE-256||(fields[10]>0&&fields[10]<8))) ||
           fields[6]<0 || fields[6]>=SYSTEM_SAVE_SOURCES.length ||
           (fields[6]==0 ? fields[3]!=0 : fields[3]<1 || fields[3]>3))
            throw new IOException("Invalid Emberfall layout response");
        if(fields.length>=12){
            int code=fields[10],enabled=fields[11];
            if(enabled < -1 || enabled > 1 || (enabled==0)!=(code==0) ||
               ((fields.length>12 || code>0 || fields[5]!=0) && enabled!=1))
                throw new IOException("Invalid Emberfall EXT state");
            for(int i=12;i<fields.length;i++){
                if(fields[i] < -1 || fields[i] > 16*1024*1024-32)
                    throw new IOException("Invalid Emberfall EXT block length");
            }
        }
        return new SaveLayoutConfig(path,fields);
    }

    int[] fields(){
        int[] base={layerRecords,messageWidth,statusBytes,systemSaveVersion,executableLayout?1:0,okikae?1:0,
            systemSaveSource.ordinal(),executableMessageWidth?1:0,executableStatusBytes?1:0,executableOkikae?1:0,extensionStartCode};
        if(!hasExtensionDetails)return base;
        int[] fields=new int[12+extensionSizes.size()];
        System.arraycopy(base,0,fields,0,base.length);
        fields[11]=extensionEnabled;
        for(int i=0;i<extensionSizes.size();i++)fields[12+i]=extensionSizes.get(i);
        return fields;
    }
}
