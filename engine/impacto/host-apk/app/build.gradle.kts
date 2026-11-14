import java.security.MessageDigest

plugins {
    id("com.android.application")
}

// Inputs, never copied into the repository:
//   -PimpactoSource=<verified upstream checkout>           (tools/impacto/verify-upstream.py must pass)
//   -PimpactoCandidate=<.../candidate with libimpacto.so>  (output of tools/impacto/build-upstream.sh)
val missing = layout.buildDirectory.dir("missing-input").get().asFile
val impactoSource: File = providers.gradleProperty("impactoSource").map(::File).getOrElse(missing)
val impactoCandidate: File = providers.gradleProperty("impactoCandidate").map(::File).getOrElse(missing)
val repoRoot: File = rootDir.resolve("../../..").canonicalFile
// Diagnostics only: -PimpactoHostOnly=true packages WITHOUT libimpacto.so, to exercise the Java host layer on a
// device. The result cannot start the engine and is labelled "-hostonly"; never distribute it.
val hostOnly = providers.gradleProperty("impactoHostOnly").orNull == "true"
val pythonCommand = if (System.getProperty("os.name").lowercase().contains("windows")) "python" else "python3"

val verifyImpactoSource = tasks.register<Exec>("verifyImpactoSource") {
    group = "impacto"
    description = "Fails unless -PimpactoSource is the pinned, clean, unmodified upstream checkout."
    doFirst {
        if (!impactoSource.isDirectory) {
            throw GradleException("Pass -PimpactoSource=<verified Impacto checkout> (see engine/impacto/host-apk/README.md)")
        }
    }
    commandLine(pythonCommand, repoRoot.resolve("tools/impacto/verify-upstream.py").path, "--source", impactoSource.path)
}

// Assets are staged under build/, because upstream's own install step would write into the source tree.
val stageImpactoAssets = tasks.register<Sync>("stageImpactoAssets") {
    group = "impacto"
    dependsOn(verifyImpactoSource)
    into(layout.buildDirectory.dir("generated/impacto-assets"))
    from(impactoSource.resolve("profiles")) { into("profiles") }
    from(impactoSource.resolve("resources")) {
        into("resources")
        exclude("**/gamedata/**")
    }
    from(impactoSource.resolve("src/shaders")) { into("shaders") }
    from(impactoSource.resolve("gamedefinitions.lua"))
}

val stageImpactoLibrary = tasks.register<Sync>("stageImpactoLibrary") {
    group = "impacto"
    dependsOn(verifyImpactoSource)
    into(layout.buildDirectory.dir("generated/impacto-jni/arm64-v8a"))
    doFirst {
        val library = impactoCandidate.resolve("libimpacto.so")
        val reportFile = impactoCandidate.resolve("candidate.json")
        if (!library.isFile || !reportFile.isFile) {
            throw GradleException("Pass -PimpactoCandidate=<candidate dir with libimpacto.so and candidate.json>")
        }
        // The candidate must have been built from the pinned source lock, and the library must be the one it reports.
        val slurper = groovy.json.JsonSlurper()
        val report = slurper.parse(reportFile) as Map<*, *>
        val lock = slurper.parse(repoRoot.resolve("engine/impacto/source-lock.json")) as Map<*, *>
        if (report["status"] != "candidate-not-integrated" || report["sourceLock"] != lock) {
            throw GradleException("candidate.json was not produced from engine/impacto/source-lock.json")
        }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(library.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (report["sha256"] != sha256) {
            throw GradleException("libimpacto.so does not match the SHA-256 in candidate.json")
        }
    }
    from(impactoCandidate) { include("libimpacto.so") }
}

android {
    namespace = "com.tyranor.next.impacto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tyranor.next.impacto"
        // Android 11+ only: the host needs all-files access to read the game folder through a path.
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = if (hostOnly) "0.1.0-hostonly" else "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main") {
        // Unmodified upstream sources: ImpactoActivity and the SDL3 org.libsdl.app Java layer. Not copied, not edited.
        java.directories.add(impactoSource.resolve("android/app/src/main/java").path)
        assets.directories.add(layout.buildDirectory.dir("generated/impacto-assets").get().asFile.path)
        // Host-only diagnostics must never pick up a stale staged library left in build/ by an earlier run.
        if (!hostOnly) {
            jniLibs.directories.add(layout.buildDirectory.dir("generated/impacto-jni").get().asFile.path)
        }
    }

    // Upstream stores these uncompressed so the engine can stream them.
    androidResources { noCompress += listOf("mp4", "mpk") }

    buildTypes {
        release {
            // JNI looks up ImpactoActivity methods by name; keep everything unobfuscated.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation("junit:junit:4.13.2")
}

// Only the final packaging needs the staged assets and library; compiling Java and running unit tests does not.
afterEvaluate {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach { dependsOn(stageImpactoAssets) }
    if (!hostOnly) {
        tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }.configureEach { dependsOn(stageImpactoLibrary) }
    }
    // Everything that reads the upstream sources (compile, lint, unit tests) goes through preBuild.
    tasks.named("preBuild") { dependsOn(verifyImpactoSource) }
}
