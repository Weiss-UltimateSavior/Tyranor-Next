package buildlogic;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkParameters;

/** Isolated-worker parameters for {@link CompressXzWorkAction}. */
public interface CompressXzParameters extends WorkParameters {

    RegularFileProperty getSourceFile();

    RegularFileProperty getDestinationFile();

    /** Scratch directory under the build directory; never inside the packaged asset tree. */
    DirectoryProperty getTemporaryDirectory();

    Property<Integer> getPreset();
}
