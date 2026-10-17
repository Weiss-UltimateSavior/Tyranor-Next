package com.core.ethornell;

import org.junit.Test;
import static org.junit.Assert.*;

public class EthornellFailureDetailTest {
    @Test public void keepsOnlyNativeStatusAndKnownRedactedDetail() {
        assertEquals("status=-5 detail=filesystem policy stage=104 errno=13",
                EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -5: filesystem policy stage=104 errno=13")));
        for (String detail : new String[]{"unsupported host/game capability", "GPU surface/device initialization or rendering failed",
                "audio initialization or suspension failed", "runtime resource/VM operation failed"})
            assertEquals("status=-9 detail=" + detail, EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -9: " + detail)));
        assertEquals("status=-7 detail_state=absent", EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -7")));
        assertEquals("status=-7 detail=GPU stage=3 reason=unsupported_limits max_texture_dimension_2d=8192/4096",
                EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -7: GPU stage=3 reason=unsupported_limits max_texture_dimension_2d=8192/4096")));
    }
    @Test public void rejectsRawPathsDialogueControlsAndUnboundedMessages() {
        for (String raw : new String[]{"/private/user/game", "dialogue 日本語", "Ethornell status -5: stage\nforged_event",
                "Ethornell status -5: " + "x".repeat(2101)})
            assertEquals("status=host_failure", EthornellFailureDetail.safe(new IllegalStateException(raw)));
        assertEquals("status=-5 detail_state=rejected_category", EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -5: /private/user/game")));
        assertEquals("status=-7 detail_state=rejected_category", EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -7: GPU stage=7 reason=device_lost /private/user/game")));
        assertEquals("status=host_failure", EthornellFailureDetail.safe(new IllegalStateException()));
    }
    @Test public void acceptsOnlyBoundedNumericVmContextOnKnownRedactedClass() {
        for (int stop : new int[]{13, 14, 15}) {
            String detail = "runtime resource/VM operation failed stop=" + stop + " pc=1234 offset=5678 group=128 id=60";
            assertEquals("status=-9 detail=" + detail, EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -9: " + detail)));
        }
        String sentinel = "unsupported host/game capability stop=15 pc=18446744073709551615 offset=0 group=4294967295 id=4294967295";
        assertEquals("status=-10 detail=" + sentinel, EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -10: " + sentinel)));
        for (int cause = 0; cause <= 8; cause++) {
            String detail = "runtime resource/VM operation failed stop=15 pc=1234 offset=5678 group=128 id=60 cause=" + cause;
            assertEquals("status=-9 detail=" + detail, EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -9: " + detail)));
            for (int origin = 0; origin <= 5; origin++)
                assertEquals("status=-9 detail=" + detail + " origin=" + origin,
                        EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -9: " + detail + " origin=" + origin)));
        }
        for (String invalid : new String[]{"stop=16 pc=1 offset=2 group=3 id=4", "stop=15 pc=-1 offset=2 group=3 id=4",
                "stop=15 pc=18446744073709551616 offset=2 group=3 id=4", "stop=15 pc=1 offset=2 group=256 id=4",
                "stop=15 pc=1 offset=2 group=3 id=65536", "stop=15 pc=1 offset=2 group=3 id=4 /private/game",
                "stop=15 pc=1 offset=2 group=3 id=4 cause=9", "stop=15 pc=1 offset=2 group=3 id=4 cause=-1",
                "stop=15 pc=1 offset=2 group=3 id=4 cause=1 origin=6", "stop=15 pc=1 offset=2 group=3 id=4 origin=1",
                "stop=15 pc=1 offset=2 group=3 id=4 cause=1 /private/game",
                "stop=15 pc=dialogue offset=2 group=3 id=4"})
            assertEquals("status=-9 detail_state=rejected_vm_fields", EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -9: runtime resource/VM operation failed " + invalid)));
        assertEquals("status=-9 detail_state=rejected_category", EthornellFailureDetail.safe(new IllegalStateException("Ethornell status -9: /private/game stop=15 pc=1 offset=2 group=3 id=4")));
    }
}
