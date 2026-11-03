import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.netty.transport)
    api(libs.kotlinx.coroutines)

    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        javaParameters = true
        freeCompilerArgs.add("-Xjdk-release=21")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

val apicheck = providers.gradleProperty("apicheck")

if (apicheck.isPresent) {
    val supportedApis =
        mapOf(
            "1.21.11" to libs.versions.paper.get(),
            "26.2" to "26.2.build.129-stable",
            "26.3" to "26.3.build.187-stable",
        )

    val apiClasspaths =
        supportedApis.mapValues { (name, version) ->
            configurations.create("api${name.replace(".", "_")}") {
                isCanBeConsumed = false
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
                    attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
                }
            }.also { configuration ->
                dependencies.add(configuration.name, "io.papermc.paper:paper-api:$version")
            }
        }

    val checkApi by tasks.registering(Exec::class) {
        group = "verification"
        description = "Checks every Paper and Adventure member kScaler calls exists on each supported server version."
        dependsOn(tasks.compileKotlin)
        val classes = layout.buildDirectory.dir("classes/kotlin/main")
        val listDir = layout.buildDirectory.dir("api-classpaths")
        inputs.files(apiClasspaths.values)
        doFirst {
            apiClasspaths.forEach { (name, configuration) ->
                listDir.get().file("$name.txt").asFile.apply { parentFile.mkdirs() }.writeText(configuration.joinToString("\n"))
            }
        }
        executable = "python3"
        args(
            listOf("-I", apicheck.get(), classes.get().asFile.path) +
                supportedApis.keys.map { listDir.get().file("$it.txt").asFile.path },
        )
    }

    tasks.check {
        dependsOn(checkApi)
    }
}

tasks.jar {
    archiveFileName = "kScaler-${project.version}.jar"
}

tasks.named<Jar>("sourcesJar") {
    archiveFileName = "kScaler-${project.version}-sources.jar"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kScaler"
            from(components["java"])
            pom {
                name = "kScaler"
                description = "Server-side GUI scale detection for Paper and Folia."
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
