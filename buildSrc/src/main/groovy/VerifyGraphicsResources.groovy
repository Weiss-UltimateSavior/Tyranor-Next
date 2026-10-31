import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*
import java.security.MessageDigest

/** Verify committed local artifacts against their contribution's provenance, without downloading sources. */
abstract class VerifyGraphicsResources extends DefaultTask {
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getManifestsDirectory()
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getDescriptorsDirectory()
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getArtifacts()
    @Internal abstract DirectoryProperty getEngineDirectory()

    @TaskAction void verify() {
        verifyResources(engineDirectory.get().asFile, manifestsDirectory.get().asFile,
                descriptorsDirectory.get().asFile, artifacts.files)
    }

    static void verifyResources(File engineDirectory, File manifestsDirectory,
                                File descriptorsDirectory, Collection<File> artifacts) {
        File engine = engineDirectory.canonicalFile
        File assets = new File(engine, 'src/main/assets').canonicalFile
        Set<File> inputs = artifacts.collect { it.canonicalFile } as Set
        Set<File> verified = new HashSet<>()
        Set<File> ownedAssetDirectories = new HashSet<>()
        manifestsDirectory.eachFileRecurse { manifest ->
            if (!manifest.isFile() || !manifest.name.endsWith('.json')) return
            def data = new JsonSlurper().parse(manifest)
            (data.generated ?: []).each { item ->
                File asset = new File(assets, item.file).canonicalFile
                VerifyGraphicsResources.verifyFile(engine, inputs, verified, asset, item.sha256)
                if (asset.name.endsWith('.glsl')) {
                    ownedAssetDirectories.add(VerifyGraphicsResources.requireAssetDirectory(assets, asset.parentFile))
                }
            }
            if (data.files != null) {
                if (!(data.assetDirectory instanceof String)) throw new GradleException('Missing assetDirectory: ' + manifest)
                File directory = VerifyGraphicsResources.requireAssetDirectory(assets, new File(assets, data.assetDirectory))
                ownedAssetDirectories.add(directory)
                data.files.each { item ->
                    VerifyGraphicsResources.verifyFile(engine, inputs, verified, new File(directory, item.file), item.sha256)
                }
            }
            // localPath is repository-root relative. External-only source metadata remains descriptive.
            (data.sources ?: []).each { source ->
                boolean retained = source.verification == 'retained'
                if (retained && (!(source.files instanceof List) || source.files.isEmpty()
                        || !(source.license instanceof Map) || !source.license.localPath)) {
                    throw new GradleException('Missing retained graphics sources or license: ' + manifest)
                }
                def records = (source.files ?: []) + (source.license?.localPath ? [source.license] : [])
                records.each { item ->
                    if (retained && !item.containsKey('localPath')) {
                        throw new GradleException('Missing retained graphics source localPath: ' + manifest)
                    }
                    if (item.containsKey('localPath')) {
                        if (!(item.localPath instanceof String) || !item.localPath.trim()
                                || new File(item.localPath).isAbsolute() || item.localPath.startsWith('/')
                                || item.localPath ==~ /(?i)^[a-z]:.*/ || item.localPath.startsWith('\\')) {
                            throw new GradleException('Invalid graphics source localPath: ' + item.localPath)
                        }
                        VerifyGraphicsResources.verifyFile(engine, inputs, verified,
                                new File(engine.parentFile, item.localPath), item.sha256)
                    }
                }
            }
            (data.patches ?: []).each { item ->
                VerifyGraphicsResources.verifyFile(engine, inputs, verified, new File(manifest.parentFile, item.file), item.sha256)
            }
            if (data.previousExportsSha256 != null) {
                VerifyGraphicsResources.verifyFile(engine, inputs, verified, new File(engine.parentFile, data.previousExportsFile), data.previousExportsSha256)
            }
            if (data.librarySha256 != null) {
                VerifyGraphicsResources.verifyFile(engine, inputs, verified, new File(engine.parentFile, data.libraryFile), data.librarySha256)
            }
        }
        // Every descriptor asset must be both available and covered by its contribution's provenance.
        descriptorsDirectory.eachFile { descriptor ->
            if (!descriptor.isFile() || !descriptor.name.endsWith('.json')) return
            def data = new JsonSlurper().parse(descriptor)
            data.filters.each { definition ->
                if (definition.asset == null) return
                File asset = GenerateGraphicsFilters.requireAsset(assets, definition.asset)
                if (!verified.contains(asset)) {
                    throw new GradleException('Unregistered graphics descriptor asset: ' + definition.asset)
                }
            }
        }
        // Contributions own their declared assetDirectory, or the directories containing generated shaders.
        // Keep unrelated engine resources as task inputs without requiring graphics-filter provenance.
        Set<File> unlisted = inputs.findAll { file ->
            file.name.endsWith('.glsl') && !verified.contains(file) &&
                    ownedAssetDirectories.any { file.toPath().startsWith(it.toPath()) }
        } as Set
        if (!unlisted.isEmpty()) {
            throw new GradleException('Unregistered graphics shaders: ' + unlisted.sort())
        }
    }

    static File requireAssetDirectory(File assets, File directory) {
        File target = directory.canonicalFile
        if (!target.toPath().startsWith(assets.toPath())) {
            throw new GradleException('Invalid graphics asset directory: ' + directory)
        }
        return target
    }

    static void verifyFile(File engine, Set<File> inputs, Set<File> verified, File file, Object expected) {
        File target = file.canonicalFile
        if (!target.toPath().startsWith(engine.toPath()) || !inputs.contains(target) || !target.isFile()
                || !(expected instanceof String) || !(expected ==~ /[0-9a-f]{64}/)) {
            throw new GradleException('Invalid graphics artifact record: ' + file)
        }
        MessageDigest sha = MessageDigest.getInstance('SHA-256')
        target.withInputStream { stream ->
            byte[] buffer = new byte[65536]
            int count
            while ((count = stream.read(buffer)) != -1) sha.update(buffer, 0, count)
        }
        if (sha.digest().encodeHex().toString() != expected) {
            throw new GradleException('Graphics artifact SHA-256 mismatch: ' + target)
        }
        verified.add(target)
    }
}
