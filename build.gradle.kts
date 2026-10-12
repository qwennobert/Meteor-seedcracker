plugins {
    alias(libs.plugins.fabric.loom)
}

base {
    archivesName = properties["archives_base_name"] as String
    // Every GitHub Actions build gets its own version (and jar name), e.g. 0.2.0+build.12
    version = libs.versions.mod.version.get() +
        (providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.let { "+build.$it" } ?: "")
    group = properties["maven_group"] as String
}

repositories {
    maven {
        name = "meteor-maven"
        url = uri("https://maven.meteordev.org/releases")
    }
    maven {
        name = "meteor-maven-snapshots"
        url = uri("https://maven.meteordev.org/snapshots")
    }
    exclusiveContent {
        forRepository {
            maven {
                name = "xpple"
                url = uri("https://maven.xpple.dev/maven2")
            }
        }
        filter {
            includeGroup("dev.xpple")
        }
    }
    exclusiveContent {
        forRepository {
            maven {
                name = "babbaj"
                url = uri("https://babbaj.github.io/maven")
            }
        }
        filter {
            includeGroup("dev.babbaj")
        }
    }
    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }
}

dependencies {
    // Fabric
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)

    // Meteor
    modImplementation(libs.meteor.client)

    // Required at runtime, installed by the user alongside the addon
    modCompileOnly(libs.baritone)
    modCompileOnly(libs.seedmapper) {
        isTransitive = false
    }
    // Client command API, used to ask SeedMapper for the seed (SeedMapper depends on Fabric API anyway)
    modCompileOnly(libs.fabric.api)

    // Only for the "Minecraft Client" run configuration, not part of the published mod
    modLocalRuntime(libs.baritone)
    localRuntime(libs.nether.pathfinder)
    modLocalRuntime(libs.seedmapper) {
        isTransitive = false
    }
    modLocalRuntime(libs.fabric.api)
    modLocalRuntime(libs.betterconfig)
    modLocalRuntime(libs.clientarguments)
    modLocalRuntime(libs.simplewaypoints)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdk.get().toInt())
    }
}

tasks {
    processResources {
        val propertyMap = mapOf(
            "version" to project.version,
            "mc_version" to libs.versions.minecraft.get(),
            "jdk_version" to libs.versions.jdk.get()
        )

        inputs.properties(propertyMap)

        filteringCharset = "UTF-8"

        filesMatching("fabric.mod.json") {
            expand(propertyMap)
        }
    }

    jar {
        inputs.property("archivesName", project.base.archivesName.get())

        from("LICENSE") {
            rename { "${it}_${inputs.properties["archivesName"]}" }
        }
    }

    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release = libs.versions.jdk.get().toInt()
        options.compilerArgs.add("-Xlint:deprecation")
        options.compilerArgs.add("-Xlint:unchecked")
    }
}
