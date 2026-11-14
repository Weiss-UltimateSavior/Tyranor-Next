// Standalone build: deliberately NOT included from the repository's root settings, because it needs the verified
// upstream checkout and the separately built libimpacto.so. See README.md.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "impacto-host"
include(":app")
