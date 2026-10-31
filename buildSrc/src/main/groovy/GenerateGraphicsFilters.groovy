import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

/** Catalog data comes only from independently removable descriptors. */
abstract class GenerateGraphicsFilters extends DefaultTask {
    // This pattern is also emitted into the runtime catalog; asset paths have no implicit prefix or suffix.
    static final String ASSET_PATH_PATTERN = /[A-Za-z0-9_-]+(\/[A-Za-z0-9_-]+)*\.glsl/
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getDescriptors()
    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getAssetsDirectory()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @TaskAction void generate() {
        def definitions = descriptors.get().asFile.listFiles().findAll { it.name.endsWith('.json') }
            .sort { it.name }.collectMany { new JsonSlurper().parse(it).filters }
        def ids = [] as Set
        definitions.each { d ->
            if (!(d.id ==~ /[a-z0-9_]+/) || !ids.add(d.id)) GenerateGraphicsFilters.fail('invalid or duplicate id')
            if (!(d.category instanceof String) || !d.category || !(d.shortName instanceof String)) GenerateGraphicsFilters.fail('missing label')
            ['title', 'categoryTitle'].each { field ->
                if (!['zh','en','ja'].every { d[field][it] instanceof String && d[field][it] }) GenerateGraphicsFilters.fail('missing localized label')
            }
            if (!(d.color ==~ /#[0-9A-Fa-f]{8}/) || !(d.nativeScale in [1,2]) || !(d.haloPerPass in [0,1]) || !(d.maxOutputScale in [1,2])) GenerateGraphicsFilters.fail('invalid geometry or color')
            if (d.outputSized && d.maxOutputScale < d.nativeScale) GenerateGraphicsFilters.fail('output limit below native scale')
            if (d.asset != null) GenerateGraphicsFilters.requireAsset(assetsDirectory.get().asFile, d.asset)
            if (!d.scaleModes || !d.scaleModes.every { it in ['none','fit','factor'] } || !(d.defaultScaleMode in d.scaleModes)) GenerateGraphicsFilters.fail('invalid scale modes')
            if (!(d.defaultSharpness instanceof Number) || d.defaultSharpness < 0 || d.defaultSharpness > 1) GenerateGraphicsFilters.fail('invalid sharpness')
            if (!(d.categoryOrder instanceof Integer) || !(d.order instanceof Integer)) GenerateGraphicsFilters.fail('invalid order')
            ['sharpnessAdjustable','outputSized','forceParameters','visible'].each { if (!(d[it] instanceof Boolean)) GenerateGraphicsFilters.fail('invalid flag') }
            if (d.topLeftCoordinates != null && !(d.topLeftCoordinates instanceof Boolean)) GenerateGraphicsFilters.fail('invalid coordinate origin')
            if (d.visible && d.migrationTarget != null) GenerateGraphicsFilters.fail('migration nodes must be hidden')
        }
        definitions.each { d ->
            if (d.migrationTarget != null && !definitions.any { it.id == d.migrationTarget && it.visible }) GenerateGraphicsFilters.fail('missing migration target')
        }
        definitions.sort { a,b -> a.categoryOrder <=> b.categoryOrder ?: a.category <=> b.category ?: a.order <=> b.order ?: a.id <=> b.id }
        def quoted = { value -> value == null ? 'null' : JsonOutput.toJson(value) }
        def strings = { values -> 'new String[]{' + values.collect(quoted).join(',') + '}' }
        def lines = definitions.collect { d ->
            def args = [quoted(d.id), quoted(d.category), d.categoryOrder, d.order,
                strings(['zh','en','ja'].collect { d.title[it] }), strings(['zh','en','ja'].collect { d.categoryTitle[it] }),
                quoted(d.shortName), '0x' + d.color.substring(1), quoted(d.asset), d.nativeScale, d.haloPerPass,
                d.sharpnessAdjustable, strings(d.scaleModes), quoted(d.defaultScaleMode), d.defaultSharpness,
                d.outputSized, d.forceParameters, d.visible, quoted(d.migrationTarget), d.maxOutputScale, d.topLeftCoordinates ?: false]
            '            new GraphicsFilterDefinition(' + args.join(', ') + ')'
        }
        def source = 'package com.core.gl;\n\n// Generated from engine/src/main/graphicsfilters; do not edit.\n' +
            'final class GeneratedGraphicsFilterCatalog {\n' +
            '    static final String ASSET_PATH_PATTERN = ' + quoted(ASSET_PATH_PATTERN) + ';\n' +
            '    static GraphicsFilterDefinition[] create() {\n' +
            '        return new GraphicsFilterDefinition[]{\n' + lines.join(',\n') + '\n        };\n    }\n}\n'
        new File(outputDirectory.get().asFile, 'com/core/gl/GraphicsFilterCatalog.java').delete()
        def target = new File(outputDirectory.get().asFile, 'com/core/gl/GeneratedGraphicsFilterCatalog.java')
        target.parentFile.mkdirs()
        target.setText(source, 'UTF-8')
    }
    static File requireAsset(File directory, Object asset) {
        if (!(asset instanceof String) || !(asset ==~ ASSET_PATH_PATTERN)) {
            GenerateGraphicsFilters.fail('invalid asset path: ' + asset)
        }
        File assets = directory.canonicalFile
        File target = new File(assets, asset).canonicalFile
        if (!target.toPath().startsWith(assets.toPath()) || !target.isFile()) {
            GenerateGraphicsFilters.fail('missing or unsafe asset: ' + asset)
        }
        return target
    }
    static void fail(String detail) { throw new GradleException('Graphics filter descriptor: ' + detail) }
}
