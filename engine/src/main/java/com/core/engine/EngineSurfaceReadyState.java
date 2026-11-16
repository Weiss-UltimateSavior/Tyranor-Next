package com.core.engine;

/** Pure state for the single-in-flight, generation-aware Surface probe. */
final class EngineSurfaceReadyState {
    enum CopyResult { SUCCESS, NO_DATA, TRANSIENT_ERROR, SOURCE_INVALID, PERMANENT_ERROR }
    enum Outcome { STALE, RETRY, READY, FAILURE }

    static final class Request {
        final long id;
        final long epoch;
        final long generation;

        Request(long id, long epoch, long generation) {
            this.id = id;
            this.epoch = epoch;
            this.generation = generation;
        }
    }

    private long epoch;
    private long generation;
    private long nextRequestId;
    private long inFlightId;
    private boolean active;
    private boolean ready;
    private boolean failed;
    private boolean disposed;

    boolean start(long sourceGeneration) {
        if (disposed || ready || failed) return false;
        if (active && generation == sourceGeneration) return false;
        epoch++;
        generation = sourceGeneration;
        active = true;
        return true;
    }

    boolean updateGeneration(long sourceGeneration) {
        if (disposed || ready || failed || !active || generation == sourceGeneration) return false;
        epoch++;
        generation = sourceGeneration;
        return true;
    }

    boolean invalidateCurrentSource() {
        if (disposed || ready || failed || !active) return false;
        epoch++;
        return true;
    }

    void stop() {
        if (disposed || !active) return;
        active = false;
        epoch++;
    }

    Request beginRequest() {
        if (disposed || failed || !active || ready || inFlightId != 0L) return null;
        long requestId = ++nextRequestId;
        inFlightId = requestId;
        return new Request(requestId, epoch, generation);
    }

    Outcome complete(Request request, long currentGeneration, CopyResult result) {
        if (request == null || request.id != inFlightId) return Outcome.STALE;
        inFlightId = 0L;
        if (disposed || failed || !active || request.epoch != epoch || request.generation != generation
                || currentGeneration != generation) return Outcome.STALE;
        switch (result) {
            case SUCCESS:
                ready = true;
                active = false;
                return Outcome.READY;
            case NO_DATA:
            case TRANSIENT_ERROR:
                return Outcome.RETRY;
            case SOURCE_INVALID:
            case PERMANENT_ERROR:
            default:
                return Outcome.FAILURE;
        }
    }

    void dispose() {
        if (disposed) return;
        disposed = true;
        active = false;
        epoch++;
    }

    boolean isActive() { return active; }
    boolean isReady() { return ready; }
    boolean isFailed() { return failed; }
    boolean isDisposed() { return disposed; }
    boolean hasInFlightRequest() { return inFlightId != 0L; }
    long generation() { return generation; }

    boolean fail() {
        if (disposed || ready || failed) return false;
        failed = true;
        active = false;
        epoch++;
        return true;
    }
}
