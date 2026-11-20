package buildlogic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.inject.Inject;
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
import org.gradle.process.ExecOperations;

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

    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getDynamicSymbolTool();

    @Input
    public abstract ListProperty<String> getRequiredDynamicSymbols();

    @Inject
    protected abstract ExecOperations getExecOperations();

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
            if (!getRequiredDynamicSymbols().get().isEmpty()) {
                verifyExports(zip, getLibraryNames().get().get(0));
            }
        } catch (IOException error) {
            throw new GradleException(
                    "Cannot inspect engine runtime pack '" + getPackId().get() + "' archive: " + container, error);
        }
    }

    private void verifyExports(ZipFile zip, String library) throws IOException {
        var entry = zip.getEntry(library);
        if (entry == null) {
            throw new GradleException("Engine runtime pack '" + getPackId().get() + "' is missing " + library);
        }
        var extracted = Files.createTempFile(getTemporaryDir().toPath(), "exports-", ".so");
        try {
            try (var input = zip.getInputStream(entry)) {
                Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            }
            var output = new ByteArrayOutputStream();
            getExecOperations().exec(spec -> {
                spec.commandLine(getDynamicSymbolTool().get().getAsFile(), "--dynamic", "--defined-only",
                        "--format=just-symbols", extracted);
                spec.setStandardOutput(output);
            });
            var exports = output.toString(StandardCharsets.UTF_8).lines().toList();
            var missing = getRequiredDynamicSymbols().get().stream()
                    .filter(symbol -> !exports.contains(symbol))
                    .toList();
            if (!missing.isEmpty()) {
                throw new GradleException(
                        library + " in engine runtime pack '" + getPackId().get()
                                + "' is missing dynamic exports: " + String.join(", ", missing)
                                + ". Build the siglus_rs game_launcher package for Android.");
            }
        } finally {
            Files.deleteIfExists(extracted);
        }
    }
}
