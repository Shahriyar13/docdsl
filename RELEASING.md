# Releasing docdsl

Everything needed to publish is in the repository. What is *not* here is the Maven Central publishing plugin,
and that is deliberate — see [Why the plugin is not wired in yet](#why-the-plugin-is-not-wired-in-yet).

The facts below were checked against Sonatype's and GitHub's current documentation, not recalled. Where
something could not be verified it says so.

## Which route, and why

**Maven Central, through the Central Portal at central.sonatype.com.** Two things make it the right answer for
this library rather than merely the conventional one:

- You control `duss.app`, so the namespace `app.duss` can be verified by DNS and the artifacts keep the
  coordinates they already have. **Nothing in EasyProject has to change** — the composite build and the
  published artifact resolve `app.duss.docdsl:docdsl-openpdf:0.1.0` identically.
- Anyone can consume it anonymously.

The alternatives were investigated and rejected:

**GitHub Packages is disqualified**, and the documentation is unambiguous about why. Quoting GitHub: "In most
registries, to pull a package, you must authenticate with a personal access token or `GITHUB_TOKEN`, regardless
of whether the package is public or private." The anonymous-access exception applies only to the Container
registry, not the Gradle or Maven registries. Every consumer would need to create a classic PAT before they
could add one dependency line — and it must be a *classic* token, since "GitHub Packages only supports
authentication using a personal access token (classic)." It remains useful for one narrow purpose: a private
rehearsal to prove the pipeline works before touching an immutable registry.

**JitPack is unverified.** The research pass that was meant to settle it did not complete, so the decisive
question — whether a multi-module build is served as `com.github.<user>` or `com.github.<user>.<repo>` — is
still open. It matters, because if JitPack rewrites the group then the EasyProject dependency line and its
composite-build substitution both have to change. Do not adopt it without checking that first.

One thing worth knowing before choosing anything: **the old OSSRH service (`oss.sonatype.org`) was retired on
2025-06-30.** Any tutorial mentioning `nexusPublishing {}`, staging repositories, or "close and release" is
describing a service that no longer exists.

## One-time setup

None of this needs Gradle, so all of it can be done on this machine.

### 1. Central Portal account

Sign up at <https://central.sonatype.com>. Use a reachable email — changing it later means contacting support,
and a username can never be renamed.

### 2. Register the namespace `app.duss`

**Register `app.duss`, not `app.duss.docdsl`.** The verifier resolves the reversed domain exactly: for
`com.example` it checks `example.com` and explicitly "does not check com.example.com, maven-central.example.com,
or any other variation". Registering `app.duss.docdsl` would send it looking for `docdsl.duss.app`, which does
not exist. Verifying `app.duss` once authorises `app.duss.docdsl` and every future `app.duss.*`.

In the Portal: your username (top right) → **View Namespaces** → **Add Namespace** → `app.duss` → Submit. Copy
the Verification Key it shows. **Do not click Verify yet.**

### 3. DNS TXT record

At whatever DNS provider serves `duss.app`, add a TXT record on the **apex** — host field blank or `@`, not a
`_sonatype` subdomain — with the Verification Key as its value.

Then wait for it to actually resolve:

```bash
nslookup -type=TXT duss.app
```

Only click **Verify Namespace** once you see it. Sonatype warns that verifying early caches an NXDOMAIN and you
then have to wait for that cache to expire. Verification itself "should only take a few minutes".

### 4. Portal token

<https://central.sonatype.com/usertoken> → Generate. You get a username/password pair. **Save both
immediately — they cannot be retrieved once the modal closes.** This is not your login password, and a legacy
OSSRH token returns 401.

Put them in `%USERPROFILE%\.gradle\gradle.properties` — outside this repository, so they cannot be committed:

```properties
mavenCentralUsername=<token username>
mavenCentralPassword=<token password>
```

### 5. GPG signing key

```bash
gpg --full-generate-key
```

RSA, 4096 bits, identity `Shahriyar Aghajani <shahriyar.a13@gmail.com>`, with a passphrase and a real expiry.
Then note the key id:

```bash
gpg --list-secret-keys --keyid-format=long
```

**Push the public key to a keyserver before the first upload** — Central verifies your signatures against
them, so a key that has not propagated is a validation failure:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <LONG_KEY_ID>
```

Repeat for `keys.openpgp.org` and `pgp.mit.edu`. Then export the private key for the build:

```bash
gpg --armor --export-secret-keys <LONG_KEY_ID>
```

Add to the same external `gradle.properties`, as one line with `\n` escapes, `BEGIN`/`END` lines included:

```properties
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----
signingInMemoryKeyPassword=<passphrase>
```

Note `gpg --gen-key` defaults to a two-year validity. Expiry does not invalidate signatures already published,
but it will block the next release — set a reminder.

## Publishing

### Add the plugin

In the root `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.3.10" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}
```

Then, in **each** module, replace the existing `publishing { }` and `signing { }` blocks with:

```kotlin
plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(
        providers.gradleProperty("docdslGroup").get(),
        "docdsl-core",                                    // "docdsl-openpdf" in the other module
        providers.gradleProperty("docdslVersion").get(),
    )
    pom {
        name.set("docdsl-core")
        description.set("...")                            // keep the existing text
        inceptionYear.set("2026")
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
            url.set(providers.gradleProperty("docdslUrl"))
            connection.set(providers.gradleProperty("docdslScmConnection"))
            developerConnection.set(providers.gradleProperty("docdslScmDeveloperConnection"))
        }
    }
}
```

Four traps, each of which produces a broken release rather than an error:

1. **Delete the old `publishing { }` block.** The plugin creates a publication named `maven` from
   `components["java"]` — exactly the name the current build uses. Leaving it gives you a name collision, or a
   second publication whose POM has only a name and description, which Central rejects for missing
   `url`/`licenses`/`developers`/`scm`.
2. **Do not apply the plugin to the root project.** It has nothing publishable; at best it logs a warning, at
   worst you ship an empty `docdsl` artifact beside the real two.
3. **Keep `java { sourceCompatibility / targetCompatibility = VERSION_17 }`.** With no toolchain,
   `targetCompatibility` otherwise defaults to the JDK running Gradle (24 or 25 here) while `jvmTarget` is 17.
   Kotlin's JVM-target validation fails the build on that mismatch, and if it were silenced the artifact would
   publish `org.gradle.jvm.version=24` — so Gradle consumers on JDK 17 would get a resolution failure even
   though the bytecode is perfectly valid for them. The current build already sets this; do not remove it.
4. **`sourceCompatibility`/`targetCompatibility` is not the same as `-Xjdk-release`.** Both are in the build
   for different reasons; keep both.

### Release

Bump `docdslVersion` in `gradle.properties`, commit, tag `v0.1.0`, then from the **root**, once:

```bash
./gradlew publishToMavenCentral
```

**Run it exactly once, unqualified.** Both modules must land in one deployment bundle — the Portal validates a
bundle, not individual files, and the plugin assembles it in a build-scoped shared service's end-of-build hook.
Invoking `:docdsl-core:publish...` and then `:docdsl-openpdf:publish...` fires that hook twice and produces two
independent deployments, which is how a release ends up half-published.

Gradle cannot run from the CLI on this machine, so drive it from IntelliJ's Gradle panel or from CI.

### Verify, then publish

The deployment lands in the Portal in `VALIDATED` state. **Check it before releasing it:** open the deployment,
read the file list, and confirm both modules are present with their `.jar`, `-sources.jar`, `-javadoc.jar`,
`.pom`, `.module`, and an `.asc` for each.

Only then press **Publish**. Sonatype's own words: "Once released/published, you will not be able to
remove/update/modify your components." A `VALIDATED` or `FAILED` deployment can be dropped and the version
reused; a `PUBLISHED` one cannot. A wrong 0.1.0 is wrong forever.

## Why the plugin is not wired in yet

Adding it now would mean deleting the two `publishing { }` blocks that currently work, in favour of a
third-party plugin DSL that cannot be verified on this machine — Gradle's CLI cannot start here, so the first
real test would be someone else's build.

That risk buys nothing, because publishing is blocked on the account, DNS and GPG steps above regardless, and
those take longer than the edit does. Meanwhile the current configuration is not idle: EasyProject consumes
this library through `includeBuild`, so a broken build file here breaks that build too.

So the sequence is: finish the one-time setup, then make the plugin change as its own commit, sync it in
IntelliJ, and publish. The config above is complete — it is a paste, not a design exercise.

## Verifying without Gradle

Gradle will not start on this machine, but the Kotlin compiler will. Driving it directly is enough to prove the
sources compile, including the strict explicit-API check that publication depends on:

```bash
java -cp "<kotlin-compiler-embeddable>;<kotlin-stdlib>;<kotlin-reflect>;<kotlinx-coroutines-core-jvm>;<org.jetbrains:annotations>" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 17 -Xexplicit-api=strict \
  -cp "<kotlin-stdlib>" -d out/core docdsl-core/src/main/kotlin
```

All five jars are already in the Gradle module cache. Paths must be Windows-style, `;`-separated. Compile
`docdsl-core` first, then `docdsl-openpdf` with `openpdf-3.0.0.jar` and `out/core` on its classpath. Test
sources need `-Xexplicit-api=disable`, since Gradle's `explicitApi()` does not apply to test compilations.
