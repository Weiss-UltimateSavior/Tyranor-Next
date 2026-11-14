package com.tyranor.next.impacto;

import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.widget.Toast;

import com.committeeofzero.impacto.ImpactoActivity;

import java.io.File;

/**
 * Tyranor host for the unmodified upstream {@link ImpactoActivity}. It only overrides methods upstream already
 * exposes as public or protected hooks:
 * <ul>
 *   <li>{@code getArguments()}: the engine's own {@code -g -bp -gc -uc} command line, no {@code args.txt};</li>
 *   <li>{@code getChosenDirectory()}: the per-game data root instead of the user-picked folder;</li>
 *   <li>{@code chooseDirectory()} / {@code resetChosenDir()}: the folder is owned by the host, so the engine's
 *       picker is disabled and a reset is a no-op.</li>
 * </ul>
 * Native code calls the last three through JNI on the activity object, so these overrides are what it sees.
 * Not exported; started only by {@link ImpactoLaunchActivity} after validation.
 */
public final class TyranorImpactoActivity extends ImpactoActivity {
    private ImpactoRequest request;
    private File externalFilesDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        externalFilesDir = getExternalFilesDir(null);
        request = ImpactoIntents.read(getIntent());
        super.onCreate(savedInstanceState);
    }

    @Override
    protected String[] getArguments() {
        if (request == null || externalFilesDir == null) {
            return new String[0];
        }
        // basepaths.lua is written by ImpactoActivity.updateBasePaths() into the external files directory.
        return new String[] {
                "-g", request.profile,
                "-bp", new File(externalFilesDir, "basepaths.lua").getAbsolutePath(),
                "-gc", new File(externalFilesDir, "gamedefinitions.lua").getAbsolutePath(),
                "-uc", new File(ImpactoRequest.dataRoot(externalFilesDir, request.gameKey), "userconfig.toml")
                        .getAbsolutePath(),
        };
    }

    @Override
    public String getChosenDirectory() {
        if (request != null && externalFilesDir != null) {
            return ImpactoRequest.dataRoot(externalFilesDir, request.gameKey).getAbsolutePath();
        }
        return externalFilesDir != null ? externalFilesDir.getAbsolutePath() : super.getChosenDirectory();
    }

    @Override
    public void chooseDirectory() {
        // The game folder is chosen in Tyranor Next; the upstream folder picker stays disabled.
    }

    @Override
    public void resetChosenDir() {
        // Nothing to reset: the data root is derived from the launch request, not from saved preferences.
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        ImpactoRequest next = ImpactoIntents.read(intent);
        if (next != null && request != null && !next.gameKey.equals(request.gameKey)) {
            // One engine instance per process: do not swap games under a running native thread.
            Toast.makeText(this, R.string.another_game_running, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            // SDL keeps process-wide native state; end the process so the next launch starts clean. This activity
            // runs in its own ":game" process, so the launcher and the Tyranor Next hand-off are not affected.
            Process.killProcess(Process.myPid());
        }
    }
}
