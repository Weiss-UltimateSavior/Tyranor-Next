package com.core.gl;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Immutable filter contribution shared by protocol, executor, settings and analyzer. */
public final class GraphicsFilterDefinition {
    public final String id, category, shortName, shaderAsset, defaultScaleMode, migrationTarget;
    public final int categoryOrder, order, color, nativeScale, haloPerPass, maxOutputScale;
    public final boolean sharpnessAdjustable, outputSized, forceParameters, visible, topLeftCoordinates;
    public final double defaultSharpness;
    public final List<String> scaleModes;
    private final String[] titles, categoryTitles;

    GraphicsFilterDefinition(String id, String category, int categoryOrder, int order, String[] titles,
            String[] categoryTitles, String shortName, int color, String shaderAsset, int nativeScale,
            int haloPerPass, boolean sharpnessAdjustable, String[] scaleModes, String defaultScaleMode,
            double defaultSharpness, boolean outputSized, boolean forceParameters, boolean visible,
            String migrationTarget, int maxOutputScale) {
        this(id, category, categoryOrder, order, titles, categoryTitles, shortName, color, shaderAsset,
                nativeScale, haloPerPass, sharpnessAdjustable, scaleModes, defaultScaleMode,
                defaultSharpness, outputSized, forceParameters, visible, migrationTarget, maxOutputScale, false);
    }
    GraphicsFilterDefinition(String id, String category, int categoryOrder, int order, String[] titles,
            String[] categoryTitles, String shortName, int color, String shaderAsset, int nativeScale,
            int haloPerPass, boolean sharpnessAdjustable, String[] scaleModes, String defaultScaleMode,
            double defaultSharpness, boolean outputSized, boolean forceParameters, boolean visible,
            String migrationTarget, int maxOutputScale, boolean topLeftCoordinates) {
        this.topLeftCoordinates = topLeftCoordinates;
        this.id = id; this.category = category; this.categoryOrder = categoryOrder; this.order = order;
        this.titles = titles.clone(); this.categoryTitles = categoryTitles.clone(); this.shortName = shortName;
        this.color = color; this.shaderAsset = shaderAsset; this.nativeScale = nativeScale;
        this.haloPerPass = haloPerPass; this.sharpnessAdjustable = sharpnessAdjustable;
        this.scaleModes = Collections.unmodifiableList(Arrays.asList(scaleModes.clone()));
        this.defaultScaleMode = defaultScaleMode; this.defaultSharpness = defaultSharpness;
        this.outputSized = outputSized; this.forceParameters = forceParameters; this.visible = visible;
        this.migrationTarget = migrationTarget; this.maxOutputScale = maxOutputScale;
    }
    public boolean adjustable() { return sharpnessAdjustable || scaleModes.size() > 1; }
    public String title(String language) { return titles[languageIndex(language)]; }
    public String categoryTitle(String language) { return categoryTitles[languageIndex(language)]; }
    private static int languageIndex(String language) {
        return "zh".equals(language) ? 0 : "ja".equals(language) ? 2 : 1;
    }
}
