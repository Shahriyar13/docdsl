import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `maven-publish`
    signing
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

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    api(project(":docdsl-core"))

    // `api`, not `implementation`: a caller that wants a running header, an absolutely-positioned block or the
    // finished writer has to be able to name openpdf types. Hiding the renderer's own library would mean
    // re-exposing a parallel type for every one of them, which is the trap this design exists to avoid.
    //
    // It also means openpdf's licence reaches consumers, which is deliberate and documented in NOTICE:
    // openpdf is "MPL-2.0 OR LGPL-2.1+", used unmodified, so a consumer takes on those terms exactly as it
    // would by depending on openpdf itself.
    api("com.github.librepdf:openpdf:3.0.0")

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

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "docdsl-openpdf"
            from(components["java"])
            pom {
                name.set("docdsl-openpdf")
                description.set("Renders a docdsl document to PDF using openpdf.")
                url.set(providers.gradleProperty("docdslUrl"))
                licenses {
                    license {
                        name.set(providers.gradleProperty("docdslLicenceName"))
                        url.set(providers.gradleProperty("docdslLicenceUrl"))
                    }
                }
                developers {
                    developer {
                        id.set(providers.gradleProperty("docdslDeveloperId"))
                        name.set(providers.gradleProperty("docdslDeveloperName"))
                    }
                }
                scm {
                    connection.set(providers.gradleProperty("docdslScmConnection"))
                    developerConnection.set(providers.gradleProperty("docdslScmDeveloperConnection"))
                    url.set(providers.gradleProperty("docdslUrl"))
                }
            }
        }
    }
}

signing {
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    val keyPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, keyPassword)
        sign(publishing.publications)
    }
}
