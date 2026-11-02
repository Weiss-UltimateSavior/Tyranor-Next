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
        public final double casSharpness, bicubicB, bicubicC, lanczosAntiRingingStrength;
        public final double sharpness;
        public final double widthScale, heightScale;
        public final String scaleMode;
        public Step(String id, boolean enabled) {
            this(id, enabled, defaultCasSharpness(id), defaultBicubicB(id), defaultBicubicC(id),
                    defaultLanczosAntiRingingStrength(id), defaultScaleMode(id), 1.0, 1.0);
        }
        public Step(String id, boolean enabled, double sharpness, String scaleMode, double widthScale, double heightScale) {
            this(id, enabled,
                    ("bicubic".equals(id) || "lanczos".equals(id)) ? defaultCasSharpness(id) : sharpness,
                    "bicubic".equals(id) ? sharpness : defaultBicubicB(id),
                    "bicubic".equals(id) ? sharpness : defaultBicubicC(id),
                    "lanczos".equals(id) ? sharpness : defaultLanczosAntiRingingStrength(id),
                    scaleMode, widthScale, heightScale);
        }
        public Step(String id, boolean enabled, double casSharpness, double bicubicB, double bicubicC,
                double lanczosAntiRingingStrength, String scaleMode, double widthScale, double heightScale) {
            if (!known(id) || !Double.isFinite(casSharpness) || casSharpness < 0 || casSharpness > 1
                    || !Double.isFinite(bicubicB) || bicubicB < 0 || bicubicB > 1
                    || !Double.isFinite(bicubicC) || bicubicC < 0 || bicubicC > 1
                    || !Double.isFinite(lanczosAntiRingingStrength) || lanczosAntiRingingStrength < 0 || lanczosAntiRingingStrength > 1
                    || !Double.isFinite(widthScale) || !Double.isFinite(heightScale)
                    || widthScale < 0.25 || widthScale > 4 || heightScale < 0.25 || heightScale > 4
                    || !("none".equals(scaleMode) || "fit".equals(scaleMode) || "factor".equals(scaleMode)))
                throw new IllegalArgumentException("invalid graphics parameters");
            GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
            if (!definition.scaleModes.contains(scaleMode)
                    || (!definition.sharpnessAdjustable && (casSharpness != definition.defaultSharpness
                            || bicubicB != definition.defaultBicubicB || bicubicC != definition.defaultBicubicC
                            || lanczosAntiRingingStrength != definition.defaultLanczosAntiRingingStrength))
                    || !definition.adjustable() && (!definition.defaultScaleMode.equals(scaleMode) || widthScale != 1 || heightScale != 1))
                throw new IllegalArgumentException("parameters unsupported by filter");
            if ("none".equals(scaleMode) && (widthScale != 1 || heightScale != 1))
                throw new IllegalArgumentException("invalid scale parameters");
            this.id = id; this.enabled = enabled;
            this.casSharpness = casSharpness;
            this.bicubicB = bicubicB;
            this.bicubicC = bicubicC;
            this.lanczosAntiRingingStrength = lanczosAntiRingingStrength;
            this.sharpness = "bicubic".equals(id) ? bicubicB : "lanczos".equals(id) ? lanczosAntiRingingStrength : casSharpness;
            this.scaleMode = scaleMode; this.widthScale = widthScale; this.heightScale = heightScale;
        }
        private boolean parameterized() {
            GraphicsFilterDefinition def = GraphicsFilterDefinitions.find(id);
            if (def.forceParameters) return true;
            if (!defaultScaleMode(id).equals(scaleMode) || widthScale != 1 || heightScale != 1) return true;
            if ("bicubic".equals(id)) {
                return bicubicB != def.defaultBicubicB || bicubicC != def.defaultBicubicC;
            }
            if ("lanczos".equals(id)) {
                return lanczosAntiRingingStrength != def.defaultLanczosAntiRingingStrength;
            }
            if ("cas".equals(id)) {
                return casSharpness != def.defaultSharpness;
            }
            return sharpness != legacySharpness(id);
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
    public static double defaultCasSharpness(String id) {
        GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
        return definition == null ? 0.4 : definition.defaultSharpness;
    }
    public static double defaultBicubicB(String id) {
        GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
        return definition == null ? 0.33 : definition.defaultBicubicB;
    }
    public static double defaultBicubicC(String id) {
        GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
        return definition == null ? 0.33 : definition.defaultBicubicC;
    }
    public static double defaultLanczosAntiRingingStrength(String id) {
        GraphicsFilterDefinition definition = GraphicsFilterDefinitions.find(id);
        return definition == null ? 0.5 : definition.defaultLanczosAntiRingingStrength;
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
                else if (parameters && "bicubic".equals(fields[0]) && fields.length == 6) {
                    result.add(new Step(fields[0], enabled, defaultCasSharpness(fields[0]),
                            Double.parseDouble(fields[1]), Double.parseDouble(fields[2]),
                            defaultLanczosAntiRingingStrength(fields[0]), fields[3],
                            Double.parseDouble(fields[4]), Double.parseDouble(fields[5])));
                } else if (parameters && fields.length == 5) {
                    double val = Double.parseDouble(fields[1]);
                    if ("bicubic".equals(fields[0])) {
                        result.add(new Step(fields[0], enabled, defaultCasSharpness(fields[0]), val, val,
                                defaultLanczosAntiRingingStrength(fields[0]), fields[2],
                                Double.parseDouble(fields[3]), Double.parseDouble(fields[4])));
                    } else if ("lanczos".equals(fields[0])) {
                        result.add(new Step(fields[0], enabled, defaultCasSharpness(fields[0]), defaultBicubicB(fields[0]), defaultBicubicC(fields[0]),
                                val, fields[2], Double.parseDouble(fields[3]), Double.parseDouble(fields[4])));
                    } else {
                        result.add(new Step(fields[0], enabled, val, defaultBicubicB(fields[0]), defaultBicubicC(fields[0]),
                                defaultLanczosAntiRingingStrength(fields[0]), fields[2],
                                Double.parseDouble(fields[3]), Double.parseDouble(fields[4])));
                    }
                } else return null;
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
            if (step.parameterized()) {
                if ("bicubic".equals(step.id)) {
                    result.append('~').append(step.bicubicB).append('~').append(step.bicubicC)
                            .append('~').append(step.scaleMode).append('~').append(step.widthScale).append('~').append(step.heightScale);
                } else if ("lanczos".equals(step.id)) {
                    result.append('~').append(step.lanczosAntiRingingStrength)
                            .append('~').append(step.scaleMode).append('~').append(step.widthScale).append('~').append(step.heightScale);
                } else {
                    result.append('~').append(step.casSharpness)
                            .append('~').append(step.scaleMode).append('~').append(step.widthScale).append('~').append(step.heightScale);
                }
            }
        }
        return result.toString();
    }

    public static GraphicsFilterChain fromLegacy(String mode) {
        return parse(known(mode) && !"lanczos".equals(mode) ? "v1:" + mode : "v1:");
    }

    public static boolean known(String id) { return GraphicsFilterDefinitions.find(id) != null; }
}
