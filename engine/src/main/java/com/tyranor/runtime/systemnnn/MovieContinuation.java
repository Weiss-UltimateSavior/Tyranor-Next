package com.tyranor.runtime.systemnnn;

/** A one-shot movie handoff timed only while the player is active. The runner owns this on its worker. */
final class MovieContinuation {
    private Runnable pending;
    private long remaining;
    private long deadline=-1;

    boolean pending(){return pending!=null;}

    void start(Runnable action,long delay){
        pending=action;
        remaining=delay;
        deadline=-1;
    }

    long resume(long now){
        if(pending==null||deadline>=0)return -1;
        deadline=now+remaining;
        return deadline;
    }

    void pause(long now){
        if(deadline<0)return;
        remaining=Math.max(0,deadline-now);
        deadline=-1;
    }

    Runnable takeReady(long now){
        if(deadline<0||now<deadline)return null;
        Runnable ready=pending;
        cancel();
        return ready;
    }

    void cancel(){
        pending=null;
        deadline=-1;
    }
}
