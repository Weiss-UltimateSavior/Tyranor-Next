package com.tyranor.runtime.systemnnn;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/** Owns a JNI mailbox reference. A pointer's sign bit is data, not validity. */
final class MessagePlaneToken implements AutoCloseable {
    private final AtomicLong value;
    private final LongConsumer release;

    private MessagePlaneToken(long value,LongConsumer release){
        this.value=new AtomicLong(value);
        this.release=release;
    }
    static MessagePlaneToken adopt(long value,LongConsumer release){
        return value==0?null:new MessagePlaneToken(value,release);
    }
    long value(){return value.get();}
    @Override public void close(){
        long held=value.getAndSet(0);
        if(held!=0)release.accept(held);
    }
}
