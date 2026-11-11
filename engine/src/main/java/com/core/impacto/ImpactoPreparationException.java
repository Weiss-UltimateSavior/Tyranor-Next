package com.core.impacto;

/** Typed launch failures; the app owns user-facing presentation. */
public final class ImpactoPreparationException extends Exception {
    public enum Reason { INVALID_PROFILE, GAME_LAYOUT, RESOURCES, USER_DATA, SESSION_BUSY, PREPARATION }
    private final Reason reason;
    public ImpactoPreparationException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }
    public Reason getReason() { return reason; }
}
