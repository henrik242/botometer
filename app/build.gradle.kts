plugins {
    id("com.android.application")
    kotlin("plugin.serialization")
}

/**
 * Signering for sideloading. Har du egen keystore, legg dette i ~/.gradle/gradle.properties:
 *
 *   botometer.keystore=/sti/til/botometer.jks
 *   botometer.keystorePassword=...
 *   botometer.keyAlias=botometer
 *   botometer.keyPassword=...
 *
 * Uten dem faller release tilbake på debug-nøkkelen. APK-en blir da fullt installerbar - men
 * debug-nøkkelen er offentlig kjent, så en signatur fra den beviser ingenting om opphav.
 * Den er god nok til å installere selv, ikke god nok til å dele videre.
 */
val keystorePath = (findProperty("botometer.keystore") as String?)?.takeIf { file(it).exists() }

/**
 * Versjonen kommer fra git. Uten Play Console er en APK i naturen bare en fil, og da må den selv
 * kunne fortelle nøyaktig hvilken kildekode den er bygget fra: antall commits vokser monotont og
 * duger som versionCode, mens navnet tar med kortsha og datoen commiten ble laget.
 *
 * Commit-datoen, ikke byggetidspunktet: to bygg av samme commit skal gi samme versjon.
 *
 * Faller tilbake til 1 / "ukjent" utenfor et git-tre, f.eks. i et kildearkiv.
 */
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args) }
        .standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()

val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val commitSha = git("rev-parse", "--short", "HEAD") ?: "ukjent"
val commitDate = git("log", "-1", "--format=%cs") ?: "ukjent"

/**
 * Samme begrunnelse som for versionName: en APK eller AAB i naturen er bare en fil, og må selv
 * kunne fortelle hvilken kildekode den er bygget fra. Filnavnet er det første du ser i
 * nedlastingsmappa, lenge før du får åpnet appen og lest versjonen inni den.
 *
 * Gir botometer-<kortsha>-release.apk og tilsvarende .aab.
 */
base { archivesName = "botometer-$commitSha" }

android {
    namespace = "no.synth.botometer"
    compileSdk = 36

    defaultConfig {
        applicationId = "no.synth.botometer"
        minSdk = 26
        // Play krever gjeldende targetSdk ved opplasting til et utgivelsesspor - «must target at
        // least API level 36». Antakelsen om at distribusjon utenom Play sparte oss for
        // targetSdk-jakten holdt bare så lenge artefakten aldri skulle gjennom Play, og en
        // templat-app må dit for å vises i bilen i det hele tatt.
        //
        // Den skulle vært oppdatert uansett: oppførselen til foreground services henger på den,
        // og å ligge igjen gir subtile feil.
        targetSdk = 36
        versionCode = commitCount
        versionName = "$commitCount ($commitSha, $commitDate)"
    }

    buildFeatures { buildConfig = true }

    // android.util.Log er en stub i JVM-tester og kaster ellers ved kall. Vi tester logikk,
    // ikke logging.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric trenger de merget ressursene for å kunne starte en aktivitet.
            isIncludeAndroidResources = true
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("sideload") {
                storeFile = file(keystorePath)
                storePassword = findProperty("botometer.keystorePassword") as String?
                keyAlias = findProperty("botometer.keyAlias") as String?
                keyPassword = findProperty("botometer.keyPassword") as String?
            }
        }
    }

    buildTypes {
        debug {
            // ALLOW_ALL_HOSTS lar en vilkårlig app på telefonen binde seg til CarAppService og
            // lese posisjonen din. Aldri i en APK du installerer for å bruke i bil.
            buildConfigField("boolean", "ALLOW_ALL_CAR_HOSTS", "true")
        }
        release {
            buildConfigField("boolean", "ALLOW_ALL_CAR_HOSTS", "false")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("sideload")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    // Kotlins jvmTarget arves fra targetCompatibility med AGPs innebygde Kotlin-støtte.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // app-projected = Android Auto (telefonen projiserer til bilskjermen).
    // For Android Automotive OS byttes denne til androidx.car.app:app-automotive.
    implementation("androidx.car.app:app-projected:1.7.0")
    // app-projected 1.7.0 oppgir app kun som runtime-avhengighet, så CarAppService og resten
    // mangler på compile-classpath uten denne linjen.
    implementation("androidx.car.app:app:1.7.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // Lokal HTTP-server, så nedlastingen av satser testes uten nett og uten mocking-rammeverk.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    // Android-runtime på JVM. Trengs bare for å kjøre MainActivity gjennom livssyklusen.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")

    // play-services drar inn fragment 1.1.0 transitivt. ActivityResult-API-et krever 1.3.0,
    // og lint stopper release-bygget på det selv om aktiviteten vår ikke bruker fragmenter.
    constraints { implementation("androidx.fragment:fragment:1.8.5") }
}
