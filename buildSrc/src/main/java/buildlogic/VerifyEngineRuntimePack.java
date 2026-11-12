package buildlogic;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Validates one bundled engine-runtime pack against its sources and, when available, its staged
 * container.
 *
 * <p>{@code bundledEngineRuntimePacks} in {@code app/build.gradle.kts} is the single source of
 * truth for the pack contents. A pack that names a shared object which is not checked in under
 * {@code engine/src/main/jniLibs/arm64-v8a} would otherwise be zipped silently without that
 * library and only fail on the device, so this task fails the build with the missing names
 * instead.
 *
 * <p>The same task type is registered twice per pack: once before staging (sources only, so
 * {@code package<Pack>RuntimePack} itself rejects a stale manifest) and once after staging
 * ({@link #getContainerFile()} set), where a zero-entry zip is rejected as well. A zero-entry zip
 * would compress to a valid but useless asset and leave the engine unable to load its libraries
 * at first launch.
 */
public abstract class VerifyEngineRuntimePack extends DefaultTask {

    /** Pack identifier, e.g. {@code ijk}; used in failure messages. */
    @Input
    public abstract Property<String> getPackId();

    /** Shared object file names the pack is expected to contain. */
    @Input
    public abstract ListProperty<String> getLibraryNames();

    /** {@code engine/src/main/jniLibs/arm64-v8a}. */
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getLibraryDirectory();

    /**
     * Uncompressed (STORED) zip container staged by the matching packaging task. Absent for the
     * pre-staging source check.
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getContainerFile();

    @TaskAction
    public void verify() {
        File directory = getLibraryDirectory().get().getAsFile();
        List<String> missing = new ArrayList<>();
        for (String library : getLibraryNames().get()) {
            if (!new File(directory, library).isFile()) {
                missing.add(library);
            }
        }
        if (!missing.isEmpty()) {
            throw new GradleException(
                    "Engine runtime pack '" + getPackId().get() + "' references missing jniLibs source(s) in "
                            + directory + ": " + String.join(", ", missing)
                            + ". Add the file(s) under engine/src/main/jniLibs/arm64-v8a, or update "
                            + "bundledEngineRuntimePacks in app/build.gradle.kts together with NativeRuntimePack.");
        }

        if (!getContainerFile().isPresent()) {
            return;
        }
        File container = getContainerFile().get().getAsFile();
        try (ZipFile zip = new ZipFile(container)) {
            if (zip.size() == 0) {
                throw new GradleException(
                        "Engine runtime pack '" + getPackId().get() + "' produced an empty archive: " + container);
            }
        } catch (IOException error) {
            throw new GradleException(
                    "Cannot inspect engine runtime pack '" + getPackId().get() + "' archive: " + container, error);
        }
    }
}
