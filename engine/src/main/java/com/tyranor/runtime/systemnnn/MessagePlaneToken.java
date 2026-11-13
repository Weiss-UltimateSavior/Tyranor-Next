package com.tyranor.runtime.systemnnn;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** Owns a JNI mailbox reference. A pointer's sign bit is data, not validity. */
final class MessagePlaneToken implements AutoCloseable {
    private final AtomicLong value;
    private final LongConsumer release;
    private final long generation;

    private MessagePlaneToken(long value,long generation,LongConsumer release){
        this.value=new AtomicLong(value);
        this.generation=generation;
        this.release=release;
    }
    static MessagePlaneToken adopt(long value,long generation,LongConsumer release){
        return value==0?null:new MessagePlaneToken(value,generation,release);
    }
    boolean acceptsFrame(long frameGeneration){return generation==frameGeneration&&value.get()!=0;}
    long value(){return value.get();}
    @Override public void close(){
        long held=value.getAndSet(0);
        if(held!=0)release.accept(held);
    }
}
