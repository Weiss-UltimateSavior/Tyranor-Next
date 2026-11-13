package com.tyranor.runtime.systemnnn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class MessagePlaneTokenTest {
    private static void check(boolean value,String message){
        if(!value)throw new AssertionError(message);
    }

    @Test public void taggedPointersKeepAllBitsThroughHandoffAndRelease(){
        long[] handles={0xb400007a12345678L,Long.MIN_VALUE,-1L,0x7a12345678L};
        for(long handle:handles){
            List<Long> released=new ArrayList<>();
            AtomicReference<MessagePlaneToken> pending=new AtomicReference<>(
                MessagePlaneToken.adopt(handle,released::add));
            MessagePlaneToken ui=pending.getAndSet(null);
            check(ui!=null,"nonzero JNI pointer must reach the UI");
            check(ui.value()==handle,"pointer bits changed during handoff");
            check(pending.get()==null&&released.isEmpty(),"taking a token released it");
            ui.close();
            ui.close();
            check(released.size()==1&&released.get(0)==handle,"release lost pointer bits or ran twice");
        }
    }

    @Test public void replacingPendingTokenDoesNotReleaseConsumedToken(){
        List<Long> released=new ArrayList<>();
        AtomicReference<MessagePlaneToken> pending=new AtomicReference<>();
        MessagePlaneToken first=MessagePlaneToken.adopt(0xb400000000000001L,released::add);
        MessagePlaneToken second=MessagePlaneToken.adopt(0xb400000000000002L,released::add);
        pending.set(first);
        pending.getAndSet(second).close();
        MessagePlaneToken ui=pending.getAndSet(null);
        check(released.size()==1&&released.get(0)==0xb400000000000001L,"displaced token leaked");
        check(ui==second&&ui.value()==0xb400000000000002L,"replacement was lost");
        check(pending.getAndSet(null)==null,"producer retained ownership after take");
        check(released.size()==1,"producer released the UI token");
        ui.close();
        check(released.size()==2&&released.get(1)==0xb400000000000002L,"UI token leaked");
    }

    @Test public void concurrentCloseReleasesExactlyOnce() throws Exception {
        AtomicInteger releases=new AtomicInteger();
        AtomicReference<Long> released=new AtomicReference<>();
        MessagePlaneToken token=MessagePlaneToken.adopt(0xb400007a12345678L,value->{
            released.set(value);releases.incrementAndGet();
        });
        CountDownLatch start=new CountDownLatch(1);
        Thread[] threads=new Thread[8];
        for(int i=0;i<threads.length;i++){
            threads[i]=new Thread(()->{
                try{start.await();token.close();}catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            threads[i].start();
        }
        start.countDown();
        for(Thread thread:threads)thread.join();
        check(releases.get()==1&&released.get()==0xb400007a12345678L,"native reference released incorrectly");
        check(token.value()==0,"closed token remains usable");
    }

    @Test public void zeroIsTheOnlyAbsentToken(){
        check(MessagePlaneToken.adopt(0,value->{throw new AssertionError("null pointer released");})==null,
            "zero pointer must be absent");
    }

}
