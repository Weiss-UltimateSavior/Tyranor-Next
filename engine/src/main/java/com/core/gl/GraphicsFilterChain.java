package com.core.gl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Strict filter wire protocol. The Anime4K host class names are historical, not a restriction on contributed filters. */
public final class GraphicsFilterChain {
    public static final int MAX_STEPS = 8;

    public static final class Step {
        public final String id;
        public final boolean enabled;
        public final double sharpness, widthScale, heightScale;
        public final String scaleMode;
        public Step(String id, boolean enabled) {
            this(id, enabled, legacySharpness(id), defaultScaleMode(id), 1.0, 1.0);
        }
        public Step(String id, boolean enabled, double sharpness, String scaleMode, double widthScale, double heightScale) {
            if (!known(id) || !Double.isFinite(sharpness) || sharpness < 0 || sharpness > 1
                    || !Double.isFinite(widthScale) || !Double.isFinite(heightScale)
                    || widthScale < 0.25 || widthScale > 4 || heightScale < 0.25 || heightScale > 4
                    || !("none".equals(scaleMode) || "fit".equals(scaleMode) || "factor".equals(scaleMode)))
                throw new IllegalArgumentException("invalid graphics parameters");
            GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
            if (!definition.scaleModes.contains(scaleMode) || !definition.sharpnessAdjustable && sharpness != definition.defaultSharpness
                    || !definition.adjustable() && (!definition.defaultScaleMode.equals(scaleMode) || widthScale != 1 || heightScale != 1))
                throw new IllegalArgumentException("parameters unsupported by filter");
            if ("none".equals(scaleMode) && (widthScale != 1 || heightScale != 1))
                throw new IllegalArgumentException("invalid scale parameters");
            this.id = id; this.enabled = enabled; this.sharpness = sharpness;
            this.scaleMode = scaleMode; this.widthScale = widthScale; this.heightScale = heightScale;
        }
        private boolean parameterized() {
            return GraphicsFilterDefinitions.find(id).forceParameters || sharpness != legacySharpness(id)
                    || !defaultScaleMode(id).equals(scaleMode) || widthScale != 1 || heightScale != 1;
        }
    }
    public static String defaultScaleMode(String id) {
        GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
        return definition == null ? "none" : definition.defaultScaleMode;
    }
    public static double legacySharpness(String id) {
        GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
        return definition == null ? 0.4 : definition.defaultSharpness;
    }

    private final List<Step> steps;
    private GraphicsFilterChain(List<Step> steps) {
        this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
    }

    /** Authoritative builder for host requests and wire decoding; retains order, duplicates and paused nodes. */
    public static GraphicsFilterChain of(List<Step> steps) {
        if (steps == null || steps.size() > MAX_STEPS) throw new IllegalArgumentException("invalid graphics chain");
        List<Step> copy = new ArrayList<>(steps);
        if (copy.size() > MAX_STEPS || copy.contains(null)) {
            throw new IllegalArgumentException("invalid graphics chain");
        }
        return new GraphicsFilterChain(copy);
    }

    /** Null means the whole protocol is invalid; never silently drop unknown nodes. */
    public static GraphicsFilterChain parse(String encoded) {
        if (encoded == null || !(encoded.startsWith("v1:") || encoded.startsWith("v2:"))) return null;
        boolean parameters = encoded.startsWith("v2:");
        List<Step> result = new ArrayList<>();
        String payload = encoded.substring(3);
        if (payload.isEmpty()) return of(result);
        String[] tokens = payload.split(",", -1);
        if (tokens.length > MAX_STEPS) return null;
        try {
            for (String token : tokens) {
                boolean enabled = !token.startsWith("!");
                String[] fields = (enabled ? token : token.substring(1)).split("~", -1);
                if (fields.length == 1) result.add(new Step(fields[0], enabled));
                else if (parameters && fields.length == 5)
                    result.add(new Step(fields[0], enabled, Double.parseDouble(fields[1]), fields[2],
                            Double.parseDouble(fields[3]), Double.parseDouble(fields[4])));
                else return null;
            }
        } catch (IllegalArgumentException invalid) { return null; }
        return of(result);
    }

    public List<Step> steps() { return steps; }
    public boolean hasActive() {
        for (int i = 0; i < steps.size(); i++) if (steps.get(i).enabled) return true;
        return false;
    }
    public String encode() {
        boolean parameters = false;
        for (Step step : steps) parameters |= step.parameterized();
        StringBuilder result = new StringBuilder(parameters ? "v2:" : "v1:");
        for (int i = 0; i < steps.size(); i++) {
            if (i != 0) result.append(',');
            Step step = steps.get(i);
            if (!step.enabled) result.append('!');
            result.append(step.id);
            if (step.parameterized()) result.append('~').append(step.sharpness).append('~').append(step.scaleMode)
                    .append('~').append(step.widthScale).append('~').append(step.heightScale);
        }
        return result.toString();
    }

    public static GraphicsFilterChain fromLegacy(String mode) {
        return parse(known(mode) && !"lanczos".equals(mode) ? "v1:" + mode : "v1:");
    }

    public static boolean known(String id) { return GraphicsFilterDefinitions.find(id) != null; }
}
