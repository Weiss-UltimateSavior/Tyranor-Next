import org.gradle.api.DefaultTask
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.process.ExecOperations
import java.util.Properties
import javax.inject.Inject

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) {
        file.inputStream().use { load(it) }
    }
}
fun configValue(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
        ?: ""
fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * 应用版本名：发版唯一需要手改的版本字段（issue #79）。
 * versionCode 由它推导，杜绝“只改 versionName、忘记递增 versionCode”。
 */
val appVersionName = "1.62"

/**
 * versionName -> versionCode 映射：major*1_000_000 + minor*1_000 + patch。
 * 例：1.35 -> 1_035_000；2.0.1 -> 2_000_001。
 * 非法值直接构建失败，避免问题版本流出。
 */
fun versionCodeOf(name: String): Int {
    val parts = Regex("\\d+").findAll(name).map { it.value.toInt() }.toList()
    require(parts.size >= 2) { "versionName must be major.minor[.patch]: $name" }
    val major = parts[0]
    val minor = parts.getOrElse(1) { 0 }
    val patch = parts.getOrElse(2) { 0 }
    require(minor in 0..999 && patch in 0..999) { "versionName minor/patch must be < 1000: $name" }
    val code = major * 1_000_000 + minor * 1_000 + patch
    require(code in 1..2_100_000_000) { "versionCode out of Android range: $code" }
    return code
}

val appVersionCode = versionCodeOf(appVersionName)

/** 供 CI 与本地核对：输出 "versionName versionCode"。 */
abstract class PrintAppVersionTask : DefaultTask() {
    @get:Input
    abstract val versionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @TaskAction
    fun print() {
        println("${versionName.get()} ${versionCode.get()}")
    }
}

tasks.register<PrintAppVersionTask>("printAppVersion") {
    group = "help"
    description = "Prints appVersionName and the derived appVersionCode."
    versionName.set(appVersionName)
    versionCode.set(appVersionCode)
}

val appNativePluginSourceDir = layout.projectDirectory.dir("src/main/nativeplugins")
val engineNativePluginSourceDir = rootProject.layout.projectDirectory.dir("engine/src/main/nativeplugins")
val mergedNativePluginSourceDir = layout.buildDirectory.dir("generated/nativeplugin-sources")
val bundledNativePluginAssetsDir = layout.buildDirectory.dir("generated/assets/nativeplugins")
val bundledNativePluginEngineIds = listOf("kirikiroid2", "ons", "artemis")
val engineAssetSourceDir = rootProject.layout.projectDirectory.dir("engine/src/main/assets")
val sharedEngineAssetNames = listOf(
    "__rmmz__.js",
    "__hook_rmmz_core.js",
    "__hook_rmmz_managers.js",
    "__tyrano__.js",
    "__rpg__.js",
)
val hikarinagiClientId = configValue("HIKARINAGI_CLIENT_ID")
val tyranorApplicationId = configValue("TYRANOR_APPLICATION_ID").ifBlank { "com.tyranor.next" }
val ethornellReleaseProbe = providers.gradleProperty("ethornellReleaseProbe").orNull.let { value ->
    require(value == null || value == "true" || value == "false") { "ethornellReleaseProbe must be true or false" }
    value == "true"
}
val ethornellTestBuildType = if (ethornellReleaseProbe) "release" else "debug"
val ciKeystoreFile = System.getenv("ANDROID_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val ciKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val ciKeyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val ciKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasCiReleaseSigning = listOf(
    ciKeystoreFile,
    ciKeystorePassword,
    ciKeyAlias,
    ciKeyPassword,
).all { !it.isNullOrBlank() }

val syncBundledNativePluginSources by tasks.registering(Sync::class) {
    group = "native plugins"
    description = "Merges engine-owned native plugin binaries with app-owned plugin manifests."
    duplicatesStrategy = DuplicatesStrategy.FAIL
    from(engineNativePluginSourceDir) {
        exclude("**/.DS_Store")
    }
    from(appNativePluginSourceDir) {
        exclude("**/.DS_Store")
    }
    into(mergedNativePluginSourceDir)
}

val bundledNativePluginZipTasks = bundledNativePluginEngineIds.map { engineId ->
    tasks.register<Zip>("package${engineId.replaceFirstChar { it.uppercase() }}NativePlugin") {
        group = "native plugins"
        description = "Packages the $engineId native engine plugin as a compressed asset."
        dependsOn(syncBundledNativePluginSources)
        from(mergedNativePluginSourceDir.map { it.dir(engineId) })
        destinationDirectory.set(bundledNativePluginAssetsDir)
        archiveFileName.set("$engineId.zip")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

val packageBundledNativePlugins by tasks.registering {
    group = "native plugins"
    description = "Packages bundled native engine plugins as compressed assets."
    dependsOn(bundledNativePluginZipTasks)
}

val syncSharedEngineAssets by tasks.registering(Sync::class) {
    group = "native plugins"
    description = "Copies shared engine-owned scripts into the app asset tree."
    from(engineAssetSourceDir) {
        include(sharedEngineAssetNames)
        includeEmptyDirs = false
    }
    // 必须落在独立子目录：Sync 会清空目标目录下所有“非本次拷贝”的文件，
    // 若与 packageXNativePlugin 的输出（generated/assets/nativeplugins）共享父目录，
    // 一旦执行顺序为 zip → sync → mergeAssets，插件 zip 会被整目录抹掉，
    // 产出缺失运行库的 APK（beta-1.21 事故根因，顺序无保证故本地难复现）。
    into(layout.buildDirectory.dir("generated/assets/engine"))
}

// Room schema 导出（m7）：app/schemas 随版本提交，后续 schema 变更必须编写显式 Migration。
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

val checkHardcodedUiStrings by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks localized string resource parity and blocks visible CJK string literals in Kotlin UI/core code."
    // Keep the verification task usable on both developer Windows machines and
    // Unix CI hosts: Python is commonly exposed as `python` on Windows and
    // `python3` elsewhere.
    val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    val pythonCommand = if (isWindows) {
        "python"
    } else {
        "python3"
    }
    val checkerScript = rootProject.layout.projectDirectory.file("tools/check-hardcoded-ui-strings.py").asFile.absolutePath
    if (isWindows) {
        // Launch through cmd.exe: Windows Python shims can reject a direct
        // CreateProcess call from an elevated Gradle process even though the
        // same interpreter is available from the shell.
        commandLine("cmd.exe", "/c", pythonCommand, checkerScript)
    } else {
        commandLine(pythonCommand, checkerScript)
    }
}

android {
    namespace = "com.tyranor.next"
    testBuildType = ethornellTestBuildType
    ndkVersion = "28.0.13004108"
    // miuix 0.9.2 传递依赖要求 compileSdk 37（本地平台为 android-37.0）
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }
    defaultConfig {
        applicationId = tyranorApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = if (ethornellReleaseProbe)
            "com.tyranor.next.releaseprobe.EthornellReleaseInstrumentation"
        else "androidx.test.runner.AndroidJUnitRunner"
        if (ethornellReleaseProbe) testProguardFiles("src/releaseProbe/proguard-rules.pro")
        buildConfigField("String", "HIKARINAGI_CLIENT_ID", hikarinagiClientId.asBuildConfigString())
    }

    signingConfigs {
        create("ciRelease") {
            if (hasCiReleaseSigning) {
                storeFile = file(ciKeystoreFile!!)
                storePassword = ciKeystorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName(
                if (hasCiReleaseSigning) "ciRelease" else "debug"
            )
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    // 内置引擎字体（MTLc3m.ttf / mincho.ttf / DroidSansFallback.ttf，直接位于 engine/src/main/assets）不得被
    // AAPT 压缩：xsystem35 对压缩过的字体读取极慢（上游明确要求字体 asset 不压缩）；xsystem4 以 SDL_LoadFile 整文件读入内存，同样不压缩。
    // 这些字体由 System35、System4 与 ONS 共用，替换字体文件须同步检查三者。
    androidResources {
      noCompress += listOf("ttf", "otf")
    }

    packaging {
      jniLibs {
        useLegacyPackaging = true
      }
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }

    // 引擎原生插件先压缩为 assets/nativeplugins/<engine>.zip，首次启动自动安装到 app 私有目录
    sourceSets {
      if (ethornellReleaseProbe) {
        // AGP's built-in Kotlin source set independently includes src/androidTest/java.
        // Clear BOTH languages on base and build-type test overlays; clearing Java alone
        // still compiles the debug Kotlin harness against this dependency-free runner.
        configureEach {
          if (name.startsWith("androidTest")) {
            java.setSrcDirs(emptyList<String>())
            kotlin.setSrcDirs(emptyList<String>())
          }
        }
      }
      getByName("main") {
        assets.directories.clear()
        assets.directories.add("src/main/assets")
        assets.directories.add("build/generated/assets")
      }
    }
}

// Test-only ABI probe: never linked or packaged into the production app.
val ethornellTestJniDir = layout.buildDirectory.dir("generated/ethornell-androidTest/jniLibs")
abstract class CompileEthornellTestProbe : DefaultTask() {
    @get:Input
    abstract val compilerPath: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val abiHeader: RegularFileProperty

    @get:OutputDirectory
    abstract val jniOutput: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val output = jniOutput.file("arm64-v8a/libethornell_test_probe.so").get().asFile
        check(output.parentFile.isDirectory || output.parentFile.mkdirs()) {
            "Cannot create instrumentation JNI output directory"
        }
        execOperations.exec {
            commandLine(compilerPath.get(),
                "--target=aarch64-linux-android26", "-shared", "-fPIC", "-std=c++17", "-nostdlib++",
                "-Wl,-z,max-page-size=16384", "-Wl,-z,common-page-size=16384",
                "-I${abiHeader.get().asFile.parentFile.absolutePath}",
                sourceFile.get().asFile.absolutePath, "-ldl", "-o", output.absolutePath)
        }.assertNormalExitValue()
    }
}
val compileEthornellTestProbe by tasks.registering(CompileEthornellTestProbe::class) {
    sourceFile.set(layout.projectDirectory.file("src/androidTest/cpp/ethornell_test_probe.cpp"))
    abiHeader.set(rootProject.layout.projectDirectory.file("engine/src/main/cpp/ethornell_android.h"))
    jniOutput.set(ethornellTestJniDir)
    // Resolve script/environment inputs during configuration, not in a captured execution lambda.
    val sdk = localProperties.getProperty("sdk.dir")
        ?: providers.environmentVariable("ANDROID_HOME").orNull
        ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
        ?: error("Android SDK required for instrumentation JNI probe")
    val ndk = providers.environmentVariable("ANDROID_NDK_HOME").orNull ?: "$sdk/ndk/28.0.13004108"
    val host = if (System.getProperty("os.name").contains("Mac")) "darwin-x86_64" else "linux-x86_64"
    compilerPath.set("$ndk/toolchains/llvm/prebuilt/$host/bin/clang++")
}
androidComponents.onVariants(androidComponents.selector().withBuildType(ethornellTestBuildType)) { variant ->
    variant.androidTest?.sources?.jniLibs?.addGeneratedSourceDirectory(
        compileEthornellTestProbe, CompileEthornellTestProbe::jniOutput)
    if (ethornellReleaseProbe) {
        variant.androidTest?.sources?.java?.addStaticSourceDirectory("src/releaseProbe/java")
    }
}

tasks.matching {
    (it.name.startsWith("merge") && it.name.endsWith("Assets")) ||
        it.name.contains("Lint", ignoreCase = true)
}.configureEach {
    dependsOn(packageBundledNativePlugins)
    dependsOn(syncSharedEngineAssets)
}

tasks.named("check") {
    dependsOn(checkHardcodedUiStrings)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
   if (!ethornellReleaseProbe) androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.work.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // 游戏库持久化（Room）：games/scan_roots/quick_launch 表，见 core/game/storage
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
    debugImplementation(libs.leakcanary)
    ksp(libs.androidx.room.compiler)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
   if (!ethornellReleaseProbe) androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.json)

  // Instrumented tests: jUnit rules and runners
   if (!ethornellReleaseProbe) {
     androidTestImplementation(libs.androidx.test.core)
     androidTestImplementation(libs.androidx.test.ext.junit)
     androidTestImplementation(libs.androidx.test.runner)
     androidTestImplementation(libs.androidx.test.espresso.core)
   }

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.exifinterface)
  implementation(libs.appauth)

  // Miuix 组件库（设置页 Card + Preference 体系）
  implementation(libs.miuix.ui)
  implementation(libs.miuix.preference)
  // 液态玻璃导航（圆角流体玻璃底部导航，参考 RinneMobile）
  implementation(libs.backdrop)
  implementation(project(":engine"))
}
