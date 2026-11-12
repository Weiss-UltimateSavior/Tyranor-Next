package buildlogic;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.gradle.api.GradleException;
import org.gradle.workers.WorkAction;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZOutputStream;

/**
 * Compresses one STORED zip container into a single LZMA2 (`.xz`) asset.
 *
 * <p>Runs in an isolated worker process: preset 9 uses a 64 MiB dictionary and needs several
 * hundred MiB of encoder state, which does not reliably fit the Gradle daemon heap during a full
 * {@code assembleRelease}. Java (not Groovy) keeps the parameters isolatable.
 *
 * <p>The in-progress stream is written to the build's scratch directory and only moved into the
 * generated asset tree once compression succeeded. Writing it next to the destination would let a
 * killed worker leave a `.tmp` file that `mergeAssets` happily packages into the APK.
 */
public abstract class CompressXzWorkAction implements WorkAction<CompressXzParameters> {

    @Override
    public void execute() {
        File source = getParameters().getSourceFile().get().getAsFile();
        if (!source.isFile()) {
            throw new GradleException("Missing archive: " + source);
        }
        File destination = getParameters().getDestinationFile().get().getAsFile();
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new GradleException("Cannot create asset directory: " + parent);
        }
        File temporaryDirectory = getParameters().getTemporaryDirectory().get().getAsFile();
        if (!temporaryDirectory.isDirectory() && !temporaryDirectory.mkdirs()) {
            throw new GradleException("Cannot create XZ scratch directory: " + temporaryDirectory);
        }
        File temp = new File(temporaryDirectory, destination.getName() + ".tmp");
        // A previously killed worker may have left the same scratch name behind; it is outside the
        // asset tree, so overwriting it is enough to keep this run deterministic.
        if (temp.isFile() && !temp.delete()) {
            throw new GradleException("Cannot clear stale XZ scratch file: " + temp);
        }
        try {
            LZMA2Options options = new LZMA2Options(getParameters().getPreset().get());
            // Hash-chain match finder: same dictionary size (cross-library redundancy) with a
            // fraction of BT4's encoder memory.
            options.setMatchFinder(LZMA2Options.MF_HC4);
            try (OutputStream raw = new FileOutputStream(temp);
                 XZOutputStream xz = new XZOutputStream(raw, options);
                 InputStream input = new FileInputStream(source)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    xz.write(buffer, 0, read);
                }
            }
            // Publish atomically-ish: the asset tree only ever sees a complete `.xz` stream.
            try {
                Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                throw new GradleException("Cannot publish compressed asset: " + destination, error);
            }
        } catch (IOException error) {
            throw new GradleException("LZMA2 compression failed for " + source, error);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }
}
