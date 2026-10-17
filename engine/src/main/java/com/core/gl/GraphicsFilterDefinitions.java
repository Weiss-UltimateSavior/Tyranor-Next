package com.core.gl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Build-generated contributions: removing a descriptor removes all of its runtime registration. */
public final class GraphicsFilterDefinitions {
    private static final Map<String, GraphicsFilterDefinition> BY_ID;
    private static final List<GraphicsFilterDefinition> VISIBLE;
    private static final List<GraphicsFilterDefinition> ALL;
    private static final Pattern ASSET_PATH = Pattern.compile(GeneratedGraphicsFilterCatalog.ASSET_PATH_PATTERN);
    static {
        Map<String, GraphicsFilterDefinition> ids = new LinkedHashMap<>();
        List<GraphicsFilterDefinition> visible = new ArrayList<>();
        for (GraphicsFilterDefinition definition : GeneratedGraphicsFilterCatalog.create()) {
            ids.put(definition.id, definition);
            if (definition.visible) visible.add(definition);
        }
        BY_ID = Collections.unmodifiableMap(ids);
        VISIBLE = Collections.unmodifiableList(visible);
        ALL = Collections.unmodifiableList(new ArrayList<>(ids.values()));
    }
    private GraphicsFilterDefinitions() {}
    public static GraphicsFilterDefinition find(String id) { return BY_ID.get(id); }
    public static List<GraphicsFilterDefinition> visible() { return VISIBLE; }
    /** Includes compatibility presets which remain executable even when hidden from the add menu. */
    public static List<GraphicsFilterDefinition> all() { return ALL; }
    /** Full, relative bundled shader path; shared with descriptor validation at build time. */
    public static boolean isValidAssetPath(String asset) {
        return asset != null && ASSET_PATH.matcher(asset).matches();
    }
}
