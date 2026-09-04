plugins {
    // AGP 9 har innebygd Kotlin-støtte, så kotlin("android") skal ikke lenger deklareres.
    id("com.android.application") version "9.4.0" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
}
