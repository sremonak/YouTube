pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories { mavenCentral(); maven(url = "https://jitpack.io") }
}
rootProject.name = "probe"
