plugins {
    `java-library`
}

val pluginVersion = providers.gradleProperty("version").get()
val velocityApiVersion = "3.4.0"
val junitVersion = "5.11.4"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityApiVersion")

    testImplementation("com.velocitypowered:velocity-api:$velocityApiVersion")
    testImplementation(platform("org.junit:junit-bom:$junitVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

val generatedSrcDir = layout.buildDirectory.dir("generated/sources/buildConstants/java/main")

val generateBuildConstants by tasks.registering {
    val outputDir = generatedSrcDir
    val versionValue = pluginVersion
    inputs.property("version", versionValue)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("dev/minelink/velocity")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildConstants.java").writeText(
            """
            package dev.minelink.velocity;

            public final class BuildConstants {
                public static final String VERSION = "$versionValue";

                private BuildConstants() {}
            }
            """.trimIndent() + "\n"
        )
    }
}

sourceSets.main {
    java.srcDir(generateBuildConstants)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
    // Velocity's PluginAnnotationProcessor returns false (leaving @Plugin unclaimed) and @Inject/@Subscribe/@DataDirectory are runtime-only.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-processing", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.jar {
    archiveBaseName.set("minelink")
    manifest {
        attributes(
            "Implementation-Title" to "MineLink",
            "Implementation-Version" to pluginVersion,
        )
    }
}
