// The root builds nothing. It only puts the Kotlin plugin on the build's classpath so each module can apply it
// without repeating the version.
plugins {
    kotlin("jvm") version "2.3.10" apply false
}
