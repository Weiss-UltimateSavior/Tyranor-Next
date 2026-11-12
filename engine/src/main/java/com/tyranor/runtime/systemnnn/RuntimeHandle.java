package com.tyranor.runtime.systemnnn;

import java.io.File;

/** Owns the committed native runtime; candidates fork state and UI previews borrow it. */
final class RuntimeHandle implements AutoCloseable {
    static final class Borrow implements AutoCloseable {
        final long token,revision;
        Borrow(long token,long revision){this.token=token;this.revision=revision;}
        @Override public void close(){NativeGame.releaseContext(token);}
    }

    private final File root;
    private long token;
    private long revision;
    private UiPreviewPool previews;
    RuntimeHandle(File root,long token){this.root=root;this.token=token;}
    synchronized Borrow borrowPreview(){
        return token==0?null:new Borrow(NativeGame.cloneContext(token),revision);
    }
    synchronized UiPreviewPool previews(){
        if(token==0)return null;
        if(previews==null)previews=new UiPreviewPool(root,this);
        return previews;
    }
    synchronized long candidate(){return token==0?0:NativeGame.forkContext(token);}
    synchronized void commit(long game){
        long next=NativeGame.retainContext(game),previous=token;token=next;
        revision++;
        if(previous!=0)NativeGame.releaseContext(previous);
    }
    @Override public void close(){
        long previous;
        UiPreviewPool pool;
        synchronized(this){previous=token;token=0;pool=previews;previews=null;}
        if(pool!=null)pool.close();
        if(previous!=0)NativeGame.releaseContext(previous);
    }
}
