package com.tyranor.runtime.systemnnn;

import android.util.AtomicFile;
import java.io.*;
import java.util.Arrays;

/** Per-game unlocks are independent of the story's save slots. */
final class GalleryStore {
    private static final int MAGIC=0x4e4e4731;
    static final int LIMIT=180224;
    private final AtomicFile file;

    GalleryStore(File path) { file=new AtomicFile(path); }

    int[] read() throws IOException {
        synchronized (GalleryStore.class) { return readFile(); }
    }

    private int[] readFile() throws IOException {
        try (DataInputStream in=new DataInputStream(new BufferedInputStream(file.openRead()))) {
            if (in.readInt()!=MAGIC) throw new IOException("鉴赏记录格式无效");
            int count=in.readInt();
            if (count<0 || count>LIMIT) throw new IOException("鉴赏记录数量无效");
            int[] keys=new int[count];
            for (int i=0;i<count;i++) {
                int key=in.readInt(),kind=key>>>28;
                if (kind<1 || kind>5 || kind==3 && (key&0x0fffffff)>=256 || kind==4 && (key&0x0fffffff)>=4096 ||
                    kind==5 && (((key>>>16)&0xfff)>=1024 || (key&0xffff)>=32) ||
                    i>0 && key<=keys[i-1]) throw new IOException("鉴赏记录项目无效");
                keys[i]=key;
            }
            if (in.read()!=-1) throw new IOException("鉴赏记录尾部无效");
            return keys;
        } catch (FileNotFoundException e) {
            if (file.getBaseFile().exists()) throw e;
            return new int[0];
        }
    }

    void write(int[] keys) throws IOException {
        synchronized (GalleryStore.class) {
            if(keys.length>LIMIT)throw new IOException("鉴赏记录达到容量上限");
            if(Arrays.equals(keys,readFile()))return;
            FileOutputStream raw=file.startWrite();
            try {
                DataOutputStream out=new DataOutputStream(new BufferedOutputStream(raw));
                out.writeInt(MAGIC);
                out.writeInt(keys.length);
                for(int key:keys)out.writeInt(key);
                out.flush();
                file.finishWrite(raw);
            } catch (IOException | RuntimeException e) {
                file.failWrite(raw);
                throw e;
            }
        }
    }
}
