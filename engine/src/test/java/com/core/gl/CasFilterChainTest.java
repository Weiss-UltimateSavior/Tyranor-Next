package com.core.gl;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CasFilterChainTest {
    @Test public void parametersRoundTripAndInvalidParametersRejectTheWholeChain() {
        GraphicsFilterChain chain = GraphicsFilterChain.parse("v2:cas~0.6~none~1.0~1.0,!lanczos~0.4~factor~1.25~0.5,s");
        assertNotNull(chain); assertEquals(0.6, chain.steps().get(0).sharpness, 0.0);
        assertEquals("factor", chain.steps().get(1).scaleMode); assertFalse(chain.steps().get(1).enabled);
        assertEquals(chain.encode(), GraphicsFilterChain.parse(chain.encode()).encode());
        for (String bad : new String[]{"v1:cas~0.6~none~1~1", "v2:cas~NaN~none~1~1", "v2:cas~1.1~none~1~1",
                "v2:lanczos~0.4~none~1~1", "v2:lanczos~0.4~factor~0~1", "v2:lanczos~0.4~fit~1~Infinity",
                "v2:cas~0.6~none~2~1", "v2:s~0.6~fit~1~1", "v2:lanczos~0.4~fit~5~1"})
            assertNull(bad, GraphicsFilterChain.parse(bad));
    }
}
