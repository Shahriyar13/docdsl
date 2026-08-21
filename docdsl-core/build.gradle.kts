import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `maven-publish`
    signing
}

// Both modules must publish under the same coordinates, so they come from gradle.properties rather than being
// written out twice and drifting apart at the next release.
group = providers.gradleProperty("docdslGroup").get()
version = providers.gradleProperty("docdslVersion").get()

repositories {
    mavenCentral()
}

// **No Java toolchain on purpose.** A toolchain would demand a JDK 17 installation and fail the build when the
// machine only has 25 — which is exactly what happened. Compiling on whatever JDK is present while EMITTING 17
// bytecode gets both halves: the library builds here, and consumers on 17 can use it.
//
// `jvmTarget` alone is not enough for that second half. It stamps the class-file version, which is a promise
// about the bytecode and none at all about the API: compiled on a JDK 25, a call to something added after 17
// succeeds here and reaches a consumer on 17 as a NoSuchMethodError. `-Xjdk-release` closes the gap by pointing
// Kotlin at the JDK 17 class library, so such a call is a compile error here instead. `options.release` below
// does the same for javac and is kept for the day a Java source appears; there are none today, so on its own
// it guards nothing.
kotlin {
    // Every public declaration is API surface once this is published, so the compiler insists on explicit
    // visibility and return types rather than letting inference decide what consumers can see. An error
    // rather than a warning, because a warning lets an accidentally-public declaration into a release and
    // there is no taking it back afterwards.
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    // Maven Central rejects a publication without both. The javadoc jar comes out empty for a Kotlin module,
    // which satisfies the requirement; generating real API docs with Dokka would be an improvement, not a
    // prerequisite.
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

// Nothing beyond the Kotlin standard library — that is this module's whole point. The document model has to be
// describable without a PDF library present, which is what lets a spreadsheet or HTML renderer consume the same
// model later, and what keeps this artifact's licensing unentangled from any renderer's.
dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Apache-2.0 expects the licence and the notice to travel with the artifact, and NOTICE states that they do.
// Without this they exist only in the git repository, so the OpenPDF attribution would never reach anyone who
// takes docdsl from a Maven repository. `withType<Jar>` covers the sources and javadoc jars too.
tasks.withType<Jar>().configureEach {
    metaInf {
        from(rootDir.resolve("LICENSE"))
        from(rootDir.resolve("NOTICE"))
    }
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

// Signing is required to publish to Maven Central and pointless everywhere else, so it switches itself on only
// when a key is supplied. Without that guard an ordinary `./gradlew build` on a machine with no key — a CI
// runner, a contributor's laptop — fails for a reason that has nothing to do with the code.
signing {
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    val keyPassword = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    if (key != null) {
        useInMemoryPgpKeys(key, keyPassword)
        sign(publishing.publications)
    }
}
