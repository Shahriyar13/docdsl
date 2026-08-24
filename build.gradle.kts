// The root builds nothing. It only puts the plugins on the build's classpath so each module can apply them
// without repeating the version.
//
// The publish plugin is deliberately NOT applied here, only made available. The root has no publishable
// component, and applying it would at best log a warning and at worst ship an empty `docdsl` artifact beside
// the two real ones.
plugins {
    kotlin("jvm") version "2.3.10" apply false
    // Sonatype ship no official Gradle plugin for the Central Portal. This is the community one whose
    // compatibility matrix actually names Gradle 9 — and, more to the point, the one that collects both
    // modules into ONE deployment bundle. The Portal validates a bundle, not individual files, so two
    // separate uploads would be two half-releases.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}
