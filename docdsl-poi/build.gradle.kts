import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    // See docdsl-core: this applies maven-publish and signing itself, so neither is listed here.
    id("com.vanniktech.maven.publish")
}

group = providers.gradleProperty("docdslGroup").get()
version = providers.gradleProperty("docdslVersion").get()

repositories {
    mavenCentral()
}

// See docdsl-core for why there is no toolchain here, and why `-Xjdk-release` is needed on top of `jvmTarget`.
kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

// Both lines stay — see docdsl-core for why removing them would publish `org.gradle.jvm.version=25` and lock
// JDK 17 consumers out of a jar that is valid for them. The sources and javadoc jars come from the plugin.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api(project(":docdsl-core"))

    // `api`, not `implementation`, for the same reason as the openpdf module: `renderBody` takes an
    // `XSSFSheet`, so a caller writing several documents into one workbook has to be able to name POI's
    // types. Hiding them would mean re-exposing a parallel type for every one, which is the trap this design
    // exists to avoid.
    //
    // POI is Apache-2.0, the same licence as this library, so nothing new reaches a consumer's licence audit.
    api("org.apache.poi:poi-ooxml:5.5.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// See docdsl-core. It matters more here: this is the artifact that carries the OpenPDF dependency, so this is
// the NOTICE a consumer actually needs to read.
tasks.withType<Jar>().configureEach {
    metaInf {
        from(rootDir.resolve("LICENSE"))
        from(rootDir.resolve("NOTICE"))
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        providers.gradleProperty("docdslGroup").get(),
        "docdsl-poi",
        providers.gradleProperty("docdslVersion").get(),
    )

    pom {
        name.set("docdsl-poi")
        description.set("Renders a docdsl document to an .xlsx workbook using Apache POI.")
        inceptionYear.set("2026")
        url.set(providers.gradleProperty("docdslUrl"))
        licenses {
            license {
                name.set(providers.gradleProperty("docdslLicenceName"))
                url.set(providers.gradleProperty("docdslLicenceUrl"))
                distribution.set(providers.gradleProperty("docdslLicenceUrl"))
            }
        }
        developers {
            developer {
                id.set(providers.gradleProperty("docdslDeveloperId"))
                name.set(providers.gradleProperty("docdslDeveloperName"))
                url.set(providers.gradleProperty("docdslUrl"))
            }
        }
        scm {
            url.set(providers.gradleProperty("docdslUrl"))
            connection.set(providers.gradleProperty("docdslScmConnection"))
            developerConnection.set(providers.gradleProperty("docdslScmDeveloperConnection"))
        }
    }
}
