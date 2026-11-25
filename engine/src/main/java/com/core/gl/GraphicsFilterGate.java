package com.core.gl;

/**
 * Launch-once filter gate shared by every host that consumes {@link GraphicsFilterChain}
 * and carries no engine identity/capability check.
 *
 * <p>The contract is identical across this class's hosts (Siglus, RealLive / AVG32 / UK2,
 * ONScripter): a disabled request, an unparsable wire string, or a chain with no active node
 * all keep the host on its classic presentation path. The wire protocol never silently drops
 * unknown nodes, so a partial parse yields {@code null} rather than a shortened chain.
 * Keeping this in one place means a future protocol change cannot leave one host behind.</p>
 *
 * <p>Hosts with their own identity/capability gate are intentionally outside this facade:
 * {@code Anime4kRuntime} and {@code ArtemisOfficialBridge} each verify the runtime/library
 * identity or device capability before they accept a chain, so folding them in would weaken
 * those checks. Such a host must add the shared gate <em>in addition to</em>, not instead of,
 * its own identity check.</p>
 *
 * <p>Pure Java, no Android dependencies: unit-testable on the JVM and safe to call from
 * any thread before the host builds its window.</p>
 */
public final class GraphicsFilterGate {
    private GraphicsFilterGate() {}

    /** @return the parsed chain to apply, or {@code null} to keep the classic path. */
    public static GraphicsFilterChain requestedChain(boolean enabled, String encoded) {
        if (!enabled) return null;
        GraphicsFilterChain chain = GraphicsFilterChain.parse(encoded);
        return chain != null && chain.hasActive() ? chain : null;
    }
}
