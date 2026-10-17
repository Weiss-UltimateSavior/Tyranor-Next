package com.core.gl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reuse same-sized intermediates only after the last read of each published version. */
final class GraphicsShaderTargets {
    final int[] slots;
    final int count;

    GraphicsShaderTargets(List<MpvHookShader.Pass> passes) {
        int[] lastReads = new int[passes.size()];
        Map<String, Integer> latest = new HashMap<>();
        for (int i = 0; i < passes.size(); i++) {
            MpvHookShader.Pass pass = passes.get(i);
            for (String bind : pass.binds) {
                Integer producer = latest.get(bind);
                if (producer != null) lastReads[producer] = i;
            }
            if (!pass.isFinalToScreen()) {
                latest.put(pass.save, i);
                lastReads[i] = i;
            }
        }
        slots = new int[passes.size()];
        Arrays.fill(slots, -1);
        List<Integer> occupiedUntil = new ArrayList<>();
        for (int i = 0; i < passes.size(); i++) {
            if (passes.get(i).isFinalToScreen()) continue;
            int slot = 0;
            // A texture read by this pass cannot also be its framebuffer attachment.
            while (slot < occupiedUntil.size() && occupiedUntil.get(slot) >= i) slot++;
            if (slot == occupiedUntil.size()) occupiedUntil.add(lastReads[i]);
            else occupiedUntil.set(slot, lastReads[i]);
            slots[i] = slot;
        }
        count = occupiedUntil.size();
    }
}
