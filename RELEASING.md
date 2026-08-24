# Releasing docdsl

Everything needed to publish is in the repository, including the Central publishing plugin. The namespace
`app.duss` is verified, so sections 2 and 3 below are done and kept only as a record.

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

### The plugin is already wired

`com.vanniktech.maven.publish` 0.37.0 is on the build: made available in the root `build.gradle.kts` with
`apply false`, applied in each module, and configured through `mavenPublishing { }`. It applies `maven-publish`
and `signing` itself, builds the sources and javadoc jars, writes a Central-valid POM, and — the part that
matters for a two-module build — collects both modules into **one** deployment bundle. The Portal validates a
bundle rather than individual files, so two separate uploads would be two half-releases.

Three things in those files must not be "tidied away":

1. **`java { sourceCompatibility / targetCompatibility = VERSION_17 }`.** With no toolchain declared,
   `targetCompatibility` otherwise follows the JDK running Gradle (25 here) while `jvmTarget` is 17. Kotlin's
   JVM-target validation fails the build on that mismatch, and silencing it instead would publish
   `org.gradle.jvm.version=25` — refusing JDK 17 consumers a jar whose bytecode is perfectly valid for them.
2. **No `withSourcesJar()` / `withJavadocJar()`.** The plugin builds both; asking twice risks a
   duplicate-artifact failure.
3. **The artifactId equals the Gradle project name.** `coordinates()` rewrites the publication's artifactId
   but not `project.name`, and the POM entry generated for `project(":docdsl-core")` comes from that name. It
   is also what EasyProject's `includeBuild` substitution matches on.

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
