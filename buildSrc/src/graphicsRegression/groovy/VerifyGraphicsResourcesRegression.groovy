import groovy.json.JsonOutput
import org.gradle.api.GradleException

import java.nio.file.Files
import java.security.MessageDigest

/** Runs the production verifier against temporary resource trees; no test framework or Android SDK needed. */
class VerifyGraphicsResourcesRegression {
    static void main(String[] args) {
        Map<String, Closure> cases = [
            'unrelated engine shaders and sibling directories are accepted': { Fixture fixture ->
                fixture.imported('filters', 'registered.glsl')
                fixture.descriptor('filters/registered.glsl')
                fixture.asset('shaders/copy.f.glsl')
                fixture.asset('another_engine/effects/render.v.glsl')
                fixture.asset('filters_extra/unrelated.glsl')
                fixture.verify()
            },
            'generated contributions coexist with unrelated engine shaders': { Fixture fixture ->
                fixture.generated('generated/registered.glsl')
                fixture.descriptor('generated/registered.glsl')
                fixture.asset('shaders/render.f.glsl')
                fixture.verify()
            },
            'unregistered shader in an imported contribution is rejected': { Fixture fixture ->
                fixture.imported('filters', 'registered.glsl')
                fixture.asset('filters/unregistered.glsl')
                expectFailure('Unregistered graphics shaders:') { fixture.verify() }
            },
            'unregistered nested shader in an imported contribution is rejected': { Fixture fixture ->
                fixture.imported('filters', 'registered.glsl')
                fixture.asset('filters/nested/unregistered.glsl')
                expectFailure('Unregistered graphics shaders:') { fixture.verify() }
            },
            'unregistered shader in a generated contribution is rejected': { Fixture fixture ->
                fixture.generated('generated/registered.glsl')
                fixture.asset('generated/unregistered.glsl')
                expectFailure('Unregistered graphics shaders:') { fixture.verify() }
            },
            'unregistered nested shader in a generated contribution is rejected': { Fixture fixture ->
                fixture.generated('generated/registered.glsl')
                fixture.asset('generated/nested/unregistered.glsl')
                expectFailure('Unregistered graphics shaders:') { fixture.verify() }
            },
            'descriptor assets outside contribution ownership still require provenance': { Fixture fixture ->
                fixture.asset('shaders/unregistered.glsl')
                fixture.descriptor('shaders/unregistered.glsl')
                expectFailure('Unregistered graphics descriptor asset:') { fixture.verify() }
            },
            'missing descriptor assets are rejected': { Fixture fixture ->
                fixture.descriptor('missing/absent.glsl')
                expectFailure('missing or unsafe asset:') { fixture.verify() }
            },
            'unsafe descriptor paths are rejected': { Fixture fixture ->
                fixture.descriptor('../outside.glsl')
                expectFailure('invalid asset path:') { fixture.verify() }
            },
            'modified registered bytes are rejected': { Fixture fixture ->
                fixture.imported('filters', 'registered.glsl')
                fixture.asset('filters/registered.glsl', 'modified after registration')
                expectFailure('Graphics artifact SHA-256 mismatch:') { fixture.verify() }
            },
            'registered local source bytes are verified': { Fixture fixture ->
                fixture.source('engine/patches/local/original.glsl')
                fixture.verify()
            },
            'modified local source bytes are rejected': { Fixture fixture ->
                File source = fixture.source('engine/patches/local/original.glsl')
                source.setText('changed source', 'UTF-8')
                expectFailure('Graphics artifact SHA-256 mismatch:') { fixture.verify() }
            },
            'missing local sources are rejected': { Fixture fixture ->
                File source = fixture.source('engine/patches/local/original.glsl')
                assert source.delete()
                expectFailure('Invalid graphics artifact record:') { fixture.verify() }
            },
            'local sources must be declared task inputs': { Fixture fixture ->
                File source = fixture.source('engine/patches/local/original.glsl')
                expectFailure('Invalid graphics artifact record:') { fixture.verify([source]) }
            },
            'local sources cannot escape the engine even when declared as inputs': { Fixture fixture ->
                File source = fixture.source('outside.glsl')
                expectFailure('Invalid graphics artifact record:') {
                    VerifyGraphicsResources.verifyResources(fixture.engine, fixture.manifests,
                            fixture.descriptors, [source])
                }
            },
            'an explicitly invalid localPath is rejected': { Fixture fixture ->
                fixture.manifest([sources: [[files: [[localPath: null, sha256: '0' * 64]]]]])
                expectFailure('Invalid graphics source localPath:') { fixture.verify() }
            },
            'absolute local paths are rejected on every host': { Fixture fixture ->
                ['/abs/x.glsl', 'C:/outside/x.glsl'].each { path ->
                    fixture.manifest([sources: [[files: [[localPath: path, sha256: '0' * 64]]]]])
                    expectFailure('Invalid graphics source localPath:') { fixture.verify() }
                }
            },
            'parent traversal cannot escape engine': { Fixture fixture ->
                fixture.manifest([sources: [[files: [[localPath: 'engine/../outside.glsl', sha256: '0' * 64]]]]])
                expectFailure('Invalid graphics artifact record:') { fixture.verify() }
            },
            'retained source cannot drop localPath': { Fixture fixture ->
                fixture.manifest([sources: [[verification: 'retained', license: [localPath: 'engine/license'], files: [[path: 'original.glsl']]]]])
                expectFailure('Missing retained graphics source localPath:') { fixture.verify() }
            },
            'retained source cannot drop license localPath': { Fixture fixture ->
                fixture.manifest([sources: [[verification: 'retained', license: [spdx: 'MIT'], files: [[localPath: 'engine/source.glsl']]]]])
                expectFailure('Missing retained graphics sources or license:') { fixture.verify() }
            },
            'retained license hash is checked': { Fixture fixture ->
                File source = fixture.source('engine/patches/local/original.glsl')
                File license = fixture.asset('local/LICENSE-MIT', 'license bytes')
                fixture.manifest([sources: [[verification: 'retained', files: [[localPath: 'engine/patches/local/original.glsl', sha256: Fixture.digest(source)]],
                    license: [localPath: 'engine/src/main/assets/local/LICENSE-MIT', sha256: '0' * 64]]]])
                expectFailure('Graphics artifact SHA-256 mismatch:') { fixture.verify() }
            },
            'external source metadata without localPath remains compatible': { Fixture fixture ->
                fixture.manifest([sources: [[files: [[path: 'external/source.glsl', sha256: '0' * 64]]]]])
                fixture.verify()
            },
            'contribution ownership cannot escape assets': { Fixture fixture ->
                fixture.manifest([assetDirectory: '../outside', files: []])
                expectFailure('Invalid graphics asset directory:') { fixture.verify() }
            },
        ]
        cases.each { name, check ->
            File root = Files.createTempDirectory('graphics-resources-regression-').toFile()
            try {
                check.call(new Fixture(root))
                println('PASS: ' + name)
            } finally {
                root.deleteDir()
            }
        }
        println('Graphics resource regressions passed: ' + cases.size())
    }

    private static void expectFailure(String message, Closure action) {
        try {
            action.call()
        } catch (GradleException failure) {
            assert failure.message.contains(message): failure.message
            return
        }
        throw new AssertionError((Object) ('Expected verifier failure containing: ' + message))
    }

    private static final class Fixture {
        final File engine
        final File assets
        final File manifests
        final File descriptors

        Fixture(File root) {
            engine = new File(root, 'engine')
            assets = new File(engine, 'src/main/assets')
            manifests = new File(engine, 'patches')
            descriptors = new File(engine, 'src/main/graphicsfilters')
            [assets, manifests, descriptors].each { it.mkdirs() }
        }

        File asset(String path, String content = 'void main() {}') {
            File target = new File(assets, path)
            target.parentFile.mkdirs()
            target.setText(content, 'UTF-8')
            return target
        }

        void imported(String directory, String name) {
            File target = asset(directory + '/' + name)
            manifest([assetDirectory: directory, files: [[file: name, sha256: digest(target)]]])
        }

        void generated(String path) {
            File target = asset(path)
            manifest([generated: [[file: path, sha256: digest(target)]]])
        }

        File source(String path) {
            File target = new File(engine.parentFile, path)
            target.parentFile.mkdirs()
            target.setText('original source', 'UTF-8')
            manifest([sources: [[files: [[localPath: path, sha256: digest(target)]]]]])
            return target
        }

        void manifest(Map data) {
            new File(manifests, 'contribution.json').setText(JsonOutput.toJson(data), 'UTF-8')
        }

        void descriptor(String path) {
            new File(descriptors, 'filters.json').setText(
                    JsonOutput.toJson([filters: [[asset: path]]]), 'UTF-8')
        }

        void verify(Collection<File> omittedInputs = []) {
            List<File> inputs = []
            assets.eachFileRecurse { file ->
                if (file.isFile() && (file.name.endsWith('.glsl') || file.name.startsWith('LICENSE'))) {
                    inputs.add(file)
                }
            }
            manifests.eachFileRecurse { file ->
                if (file.isFile() && file.name.endsWith('.glsl')) inputs.add(file)
            }
            inputs.removeAll(omittedInputs)
            VerifyGraphicsResources.verifyResources(engine, manifests, descriptors, inputs)
        }

        private static String digest(File file) {
            MessageDigest.getInstance('SHA-256').digest(file.bytes).encodeHex().toString()
        }
    }
}
