
pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroup("com.gtnewhorizons")
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

plugins {
    // gtnhsettingsconvention 2.x 与 gtnhgradle 2.x 配套；1.0.43 内部要求 gtnhgradle 1.+，
    // 而 GTNH Nexus 已不再提供 1.x（当前仅 2.0.28），故升级到官方 ExampleMod 同款版本。
    id("com.gtnewhorizons.gtnhsettingsconvention") version("2.0.20")
}
