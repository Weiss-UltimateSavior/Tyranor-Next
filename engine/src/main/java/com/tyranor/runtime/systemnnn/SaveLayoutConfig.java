package com.tyranor.runtime.systemnnn;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Effective save format stored in the game's Emberfall configuration file. */
public final class SaveLayoutConfig {
    public enum SystemSaveSource { PENDING, EXECUTABLE, TEMPLATE, GAME_FILES }
    private static final SystemSaveSource[] SYSTEM_SAVE_SOURCES=SystemSaveSource.values();
    public final String path;
    public final int layerRecords, messageWidth, statusBytes, systemSaveVersion;
    public final boolean executableLayout, okikae;
    public final boolean executableMessageWidth, executableStatusBytes, executableOkikae;
    public final SystemSaveSource systemSaveSource;

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
        if(path==null || path.isEmpty() || fields==null || fields.length!=10 ||
           fields[6]<0 || fields[6]>=SYSTEM_SAVE_SOURCES.length ||
           (fields[6]==0 ? fields[3]!=0 : fields[3]<1 || fields[3]>3))
            throw new IOException("Invalid Emberfall layout response");
        return new SaveLayoutConfig(path,fields);
    }

    int[] fields(){
        return new int[]{layerRecords,messageWidth,statusBytes,systemSaveVersion,executableLayout?1:0,okikae?1:0,
            systemSaveSource.ordinal(),executableMessageWidth?1:0,executableStatusBytes?1:0,executableOkikae?1:0};
    }
}
