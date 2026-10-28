package com.core.web;

import java.util.Collections;
import kotlin.Pair;

/** Isolated small-heap regression: one flat key must not retain all full path prefixes. */
public final class AsarChildrenMemoryProbe {
    public static void main(String[] args) {
        String parent = "p/".repeat(10_000);
        AsarChildrenIndex index = new AsarChildrenIndex(
                Collections.singletonList(new Pair<>(parent + "leaf", false)), false);
        if (index.children("").size() != 1 ||
                !index.children(parent.substring(0, parent.length() - 1)).get(0).getFirst().equals("leaf")) {
            throw new AssertionError("Deep path lookup changed");
        }
        System.out.println("DEEP_PATH_OK");
    }
}
