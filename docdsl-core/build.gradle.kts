import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "app.duss.docdsl"
version = "0.1.0"

repositories {
    mavenCentral()
}

// **No Java toolchain on purpose.** A toolchain would demand a JDK 17 installation and fail the build when the
// machine only has 25 — which is exactly what happened. Compiling on whatever JDK is present while EMITTING 17
// bytecode gets both halves: the library builds here, and consumers on 17 can use it. `options.release` makes
// javac link against the 17 API surface rather than merely stamping the class-file version, so a call to
// something newer is a compile error here instead of a NoSuchMethodError in someone else's application.
kotlin {
    // Every public declaration is API surface once this is published, so the compiler insists on explicit
    // visibility and return types rather than letting inference decide what consumers can see.
    explicitApiWarning()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// No dependencies at all — that is this module's whole point. The document model has to be describable without
// a PDF library present, which is what lets a spreadsheet or HTML renderer consume the same model later, and
// what keeps this artifact's licensing unentangled from any renderer's.
dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "docdsl-core"
            from(components["java"])
            pom {
                name.set("docdsl-core")
                description.set(
                    "A declarative document model and Kotlin builder DSL for business documents — " +
                        "tables, nested tables, totals and page structure, with no rendering dependency."
                )
            }
        }
    }
}
