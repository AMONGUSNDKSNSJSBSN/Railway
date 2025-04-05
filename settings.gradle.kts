pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven { url = uri("https://maven.neoforged.net/releases") }
        maven { url = uri("https://maven.fabricmc.net/") }
        maven { url = uri("https://maven.architectury.dev/") }
        maven { url = uri("https://maven.quiltmc.org/repository/release") }
        gradlePluginPortal()
    }
}

include("common")
include("fabric")
include("forge")


plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
