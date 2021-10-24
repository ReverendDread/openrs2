rootProject.name = "openrs2"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven(url = "https://repo.openrs2.org/repository/openrs2")
        mavenLocal()
        maven(url = "https://repo.openrs2.org/repository/openrs2-snapshots")
    }
}

pluginManagement {
    plugins {
        id("com.github.ben-manes.versions") version "0.39.0"
        id("com.github.jk1.dependency-license-report") version "2.0"
        id("com.github.johnrengelman.shadow") version "7.1.0"
        id("org.jetbrains.dokka") version "1.5.31"
        id("org.jmailen.kotlinter") version "3.6.0"
        kotlin("jvm") version "1.5.31"
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
enableFeaturePreview("VERSION_CATALOGS")

include(
    "all",
    "archive",
    "asm",
    "buffer",
    "buffer-generator",
    "cache",
    "cache-550",
    "cli",
    "compress",
    "compress-cli",
    "conf",
    "crc32",
    "crypto",
    "db",
    "decompiler",
    "deob",
    "deob-annotations",
    "deob-ast",
    "deob-bytecode",
    "deob-processor",
    "deob-util",
    "game",
    "http",
    "inject",
    "json",
    "log",
    "net",
    "nonfree",
    "nonfree:client",
    "nonfree:gl",
    "nonfree:loader",
    "nonfree:signlink",
    "nonfree:unpack",
    "nonfree:unpackclass",
    "patcher",
    "protocol",
    "util",
    "yaml"
)
