package com.tyranor.next.impacto;

import android.content.Intent;

/** Reads an {@link ImpactoRequest} out of an Intent; shared by both activities. */
final class ImpactoIntents {
    private ImpactoIntents() {
    }

    /**
     * @return the validated request, or {@code null} when the Intent carries none that is acceptable. Any
     *         RuntimeException is treated as rejection: reading extras unparcels them, so a caller that sends an
     *         extra of an unknown Parcelable class would otherwise crash this process (which also runs the game).
     */
    static ImpactoRequest read(Intent intent) {
        if (intent == null) {
            return null;
        }
        try {
            return ImpactoRequest.parse(
                    intent.getStringExtra(ImpactoRequest.EXTRA_GAME_DIR),
                    intent.getStringExtra(ImpactoRequest.EXTRA_PROFILE),
                    intent.getStringExtra(ImpactoRequest.EXTRA_GAME_KEY),
                    intent.getStringExtra(ImpactoRequest.EXTRA_TITLE));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
