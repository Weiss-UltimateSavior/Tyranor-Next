package com.core.gl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reuse same-sized intermediates only after the last read of each published version. */
final class GraphicsShaderTargets {
    final int[][] passTargetSlots;
    final int count;

    GraphicsShaderTargets(List<MpvHookShader.Pass> passes) {
        if (passes == null) {
            throw new IllegalArgumentException("null shader pass list");
        }
        int passCount = passes.size();
        Map<String, int[]> latest = new HashMap<>();
        int[][] lastReads = new int[passCount][];

        for (int i = 0; i < passCount; i++) {
            MpvHookShader.Pass pass = passes.get(i);
            lastReads[i] = pass.isFinalToScreen() ? new int[0] : new int[pass.saves.size()];
            for (String bind : pass.binds) {
                int[] producer = latest.get(bind);
                if (producer != null) {
                    lastReads[producer[0]][producer[1]] = i;
                }
            }
            if (!pass.isFinalToScreen()) {
                for (int s = 0; s < pass.saves.size(); s++) {
                    latest.put(pass.saves.get(s), new int[]{i, s});
                    lastReads[i][s] = i;
                }
            }
        }

        passTargetSlots = new int[passCount][];
        List<Integer> occupiedUntil = new ArrayList<>();
        Map<String, Integer> texToSlot = new HashMap<>();

        for (int i = 0; i < passCount; i++) {
            MpvHookShader.Pass pass = passes.get(i);
            if (pass.isFinalToScreen()) {
                passTargetSlots[i] = new int[0];
                continue;
            }

            int saveCount = pass.saves.size();
            passTargetSlots[i] = new int[saveCount];

            // A texture read by this pass cannot also be its framebuffer attachment.
            Set<Integer> forbiddenSlots = new HashSet<>();
            for (String bind : pass.binds) {
                Integer inSlot = texToSlot.get(bind);
                if (inSlot != null) {
                    forbiddenSlots.add(inSlot);
                }
            }

            Set<Integer> currentPassSlots = new HashSet<>();
            for (int s = 0; s < saveCount; s++) {
                String saveName = pass.saves.get(s);
                int lastRead = lastReads[i][s];
                int slot = 0;
                while (forbiddenSlots.contains(slot)
                        || currentPassSlots.contains(slot)
                        || (slot < occupiedUntil.size() && occupiedUntil.get(slot) >= i)) {
                    slot++;
                }

                if (slot == occupiedUntil.size()) {
                    occupiedUntil.add(lastRead);
                } else {
                    occupiedUntil.set(slot, lastRead);
                }

                texToSlot.put(saveName, slot);
                currentPassSlots.add(slot);
                passTargetSlots[i][s] = slot;
            }
        }
        count = occupiedUntil.size();
    }
}
