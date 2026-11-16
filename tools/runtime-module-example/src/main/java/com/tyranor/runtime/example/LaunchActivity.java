package com.tyranor.runtime.example;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Standalone protocol fixture; never writes outside its dedicated sample-game parent. */
public final class LaunchActivity extends Activity {
    private static final String FOLDER = "com.tyranor.runtime.extra.FOLDER_PATH";
    private static final String SETTINGS = "com.tyranor.runtime.extra.SETTINGS";
    private File gameDirectory;
    private String settingsJson;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settingsJson = getIntent().getStringExtra(SETTINGS);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        setContentView(scroll);
        addButton(content, R.string.grant_access, () -> {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            } else if (Build.VERSION.SDK_INT < 30) {
                requestPermissions(new String[] { android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1);
            }
        });
        try {
            String path = getIntent().getStringExtra(FOLDER);
            if (path == null) throw new IOException("missing folder");
            File supplied = new File(path);
            File parent = fixtureParent();
            gameDirectory = supplied.getCanonicalFile();
            if (!supplied.isAbsolute() || !parent.equals(gameDirectory.getParentFile()) || !gameDirectory.isDirectory()) {
                throw new IOException("outside example directory");
            }
            addText(content, getString(R.string.folder, gameDirectory.getPath()));
            addText(content, getString(R.string.received, settingsJson == null ? "(legacy)" : settingsJson));
            addButton(content, R.string.write_save, this::writeSave);
            addButton(content, R.string.read_save, this::readSave);
        } catch (Exception failure) {
            addText(content, getString(R.string.invalid_folder));
        }
        status = new TextView(this);
        content.addView(status);
        addButton(content, R.string.finish, this::finish);
    }

    private File fixtureParent() throws IOException {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "TyranorRuntimeExample").getCanonicalFile();
    }

    private File saveFile(boolean create) throws IOException {
        if (gameDirectory == null || !fixtureParent().equals(gameDirectory.getCanonicalFile().getParentFile())) {
            throw new IOException("game directory changed");
        }
        File saves = new File(gameDirectory, "saves");
        if (Files.isSymbolicLink(saves.toPath()) || !saves.getCanonicalFile().getParentFile().equals(gameDirectory)) {
            throw new IOException("unsafe save directory");
        }
        if (create && !saves.isDirectory() && !saves.mkdir()) throw new IOException("cannot create save directory");
        File result = new File(saves, "state.json");
        if (Files.isSymbolicLink(result.toPath())) throw new IOException("unsafe save file");
        return result;
    }

    private void writeSave() {
        try {
            JSONObject settings = new JSONObject();
            if (settingsJson != null) {
                JSONObject payload = new JSONObject(settingsJson);
                if (!Integer.valueOf(1).equals(payload.opt("version"))) throw new IOException("unsupported settings version");
                settings = payload.getJSONObject("values");
            }
            JSONObject save = new JSONObject().put("format", "tyranor-runtime-example")
                .put("settings", settings).put("writtenAt", System.currentTimeMillis());
            Files.write(saveFile(true).toPath(), save.toString(2).getBytes(StandardCharsets.UTF_8));
            status.setText(R.string.save_written);
        } catch (Exception failure) {
            status.setText(getString(R.string.save_failed, failure.getMessage()));
        }
    }

    private void readSave() {
        try {
            File file = saveFile(false);
            status.setText(file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                : getString(R.string.save_empty));
        } catch (Exception failure) {
            status.setText(getString(R.string.save_failed, failure.getMessage()));
        }
    }

    private void addText(LinearLayout parent, String text) {
        TextView view = new TextView(this);
        view.setTextIsSelectable(true);
        view.setText(text);
        parent.addView(view);
    }

    private void addButton(LinearLayout parent, int title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        parent.addView(button);
    }
}
