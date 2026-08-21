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

// See docdsl-core for why there is no toolchain here.
kotlin {
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

dependencies {
    api(project(":docdsl-core"))

    // `api`, not `implementation`: a caller that wants a running header, an absolutely-positioned block or the
    // finished writer has to be able to name openpdf types. Hiding the renderer's own library would mean
    // re-exposing a parallel type for every one of them, which is the trap this design exists to avoid.
    api("com.github.librepdf:openpdf:3.0.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "docdsl-openpdf"
            from(components["java"])
            pom {
                name.set("docdsl-openpdf")
                description.set("Renders a docdsl document to PDF using openpdf.")
            }
        }
    }
}
