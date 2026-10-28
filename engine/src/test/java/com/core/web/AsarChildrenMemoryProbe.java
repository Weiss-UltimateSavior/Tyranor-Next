package com.core.web;

import java.util.Collections;
import java.util.Iterator;
import kotlin.Pair;

/** Isolated small-heap regression: one flat key must not retain all full path prefixes. */
public final class AsarChildrenMemoryProbe {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("wide")) {
            Iterable<Pair<String, Boolean>> entries = () -> new Iterator<>() {
                private int next;
                public boolean hasNext() { return next < 50_000; }
                public Pair<String, Boolean> next() { return new Pair<>("directory/file" + next++, false); }
            };
            AsarChildrenIndex index = new AsarChildrenIndex(entries, false);
            if (index.children("directory").size() != 50_000) throw new AssertionError("Wide tree lost files");
            System.out.println("WIDE_TREE_OK");
            return;
        }
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
