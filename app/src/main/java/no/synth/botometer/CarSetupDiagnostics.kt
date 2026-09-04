package no.synth.botometer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * Svarer på «hvorfor dukker ikke appen opp i bilen?» fra telefonen, uten adb.
 *
 * Det er ikke en bekvemmelighet. Feilen viser seg i bilen, men verten sier aldri fra i appen når
 * den forkaster den - den bare utelater oppføringen. Uten en PC med adb og Desktop Head Unit har
 * brukeren da ingenting å gå på, og en sideloadet app har ingen Play Console å lese logger i.
 *
 * Sjekkene her er de samme spørringene verten selv gjør mot PackageManager, kjørt i vår egen
 * prosess. Svarer PackageManager på dem, er manifestet og R8 utelukket, og det som gjenstår er
 * vertsloggen. Det er en reell innsnevring, ikke en gjetning til.
 *
 * Alt leses fra den INSTALLERTE APK-en, ikke fra kildekoden. Det er forskjellen på å vite at
 * rettelsen ligger på telefonen og å tro det.
 */
object CarSetupDiagnostics {

    private const val PLAY = "com.android.vending"
    private const val CAR_APP_SERVICE = "androidx.car.app.CarAppService"
    private const val KATEGORI_NAVIGASJON = "androidx.car.app.category.NAVIGATION"
    private const val ANDROID_AUTO = "com.google.android.projection.gearhead"
    private const val TJENESTE = "no.synth.botometer.car.BotometerCarAppService"

    @Suppress("DEPRECATION")   // getApplicationInfo(int) og Bundle.get() er utdatert fra API 33
    fun summary(context: Context): String = buildString {
        val pm = context.packageManager

        // 0. Installasjonskilden avgjør alt annet, så den står først.
        //
        //    «Ukjente kilder» i Android Auto gjelder media-, meldings- og parkerte apper, IKKE
        //    apper bygget på Car App Library. Google sier det rett ut: innstillingen «doesn't
        //    apply to apps built using the Android for Cars App Library». En sidelastet
        //    templat-app dukker derfor aldri opp i en ekte bil, uansett hvor riktig manifestet
        //    er - og verten sier ikke fra. Den utelater bare oppføringen.
        val installkilde = installertFra(pm, context.packageName)
        if (installkilde != PLAY) {
            appendLine("✗ INSTALLERT UTENFOR PLAY (${installkilde ?: "ukjent kilde"})")
            appendLine("  Dette er grunnen, og det er ikke noe manifestet kan rette på.")
            appendLine("  «Ukjente kilder» gjelder ikke apper bygget på Car App Library.")
            appendLine("  Bruk Play Internal App Sharing - ingen review, ingen publisering.")
            appendLine("  Desktop Head Unit virker fortsatt med lokalt installert app.")
            appendLine()
        } else {
            appendLine("✓ Installert fra Play")
        }

        val egne = query(pm, Intent(CAR_APP_SERVICE))
            .filter { it.serviceInfo.packageName == context.packageName }

        // 1. Ser PackageManager tjenesten? Samme spørring som `pm query-services`, og samme
        //    spørring verten bruker for å bygge app-oversikten.
        if (egne.isEmpty()) {
            appendLine("✗ CarAppService: IKKE synlig for PackageManager.")
            appendLine("  Da ser heller ikke bilen den. Årsaken ligger i manifestet eller i R8,")
            appendLine("  ikke hos verten.")
            appendLine("  Klassen finnes i APK-en: " + if (klassenFinnes()) "ja - R8 er ikke synderen" else "NEI - R8 har strippet den")
            return@buildString
        }
        appendLine("✓ CarAppService er synlig for PackageManager")

        // 2. label og icon MÅ stå på tjenesten, ikke bare på <application>. Verten arver dem
        //    ikke, og uten dem har den ingenting å tegne en launcher-oppføring med.
        val info = egne.first().serviceInfo
        appendLine(sjekk(info.labelRes != 0 || info.nonLocalizedLabel != null, "android:label på tjenesten"))
        appendLine(sjekk(info.icon != 0, "android:icon på tjenesten"))

        // 3. Uten NAVIGATION-kategorien får appen ingen fri tegneflate, og verten sorterer den
        //    ikke som navigasjonsapp.
        val medKategori = query(pm, Intent(CAR_APP_SERVICE).addCategory(KATEGORI_NAVIGASJON))
            .any { it.serviceInfo.packageName == context.packageName }
        appendLine(sjekk(medKategori, "kategori NAVIGATION"))

        // 4. Metadata verten leser før den binder seg.
        val meta = runCatching {
            pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
        val apiNivå = meta?.get("androidx.car.app.minCarApiLevel")
        appendLine(sjekk(apiNivå != null, "minCarApiLevel (${apiNivå ?: "mangler"})"))
        appendLine(sjekk(meta?.get("com.google.android.gms.car.application") != null, "automotive_app_desc"))

        appendLine("Vertsvalidering: " + if (BuildConfig.ALLOW_ALL_CAR_HOSTS) "AV (debug-bygg)" else "på (release)")

        // 5. Vertsversjonen avgjør hvilke Car App API-nivåer som støttes.
        appendLine("Android Auto: " + (versjonAv(pm, ANDROID_AUTO) ?: "IKKE INSTALLERT"))

        // 6. To apper som deklarerer samme action gir forvirrende oppførsel i launcheren. En
        //    gjenglemt fartsbot er den nærliggende kandidaten.
        val andre = query(pm, Intent(CAR_APP_SERVICE))
            .map { it.serviceInfo.packageName }
            .filter { it != context.packageName }
            .distinct()
        if (andre.isNotEmpty()) appendLine("Andre bil-apper: ${andre.joinToString()}")

        if (info.icon != 0 && info.labelRes != 0 && medKategori) {
            appendLine()
            if (installkilde != PLAY) {
                appendLine("Manifestet er i orden. Det som gjenstår er installasjonskilden over -")
                appendLine("den, og ingenting annet, er grunnen til at appen mangler i bilen.")
            } else {
                appendLine("Alt telefonen kan se er i orden. Vises appen likevel ikke i bilen, er")
                appendLine("det verten som forkaster den, og bare vertsloggen sier hvorfor:")
                appendLine("  adb logcat | grep -iE 'GH\\.|Gearhead|CarApp'")
            }
        }
    }

    private fun sjekk(ok: Boolean, hva: String) = (if (ok) "✓ " else "✗ MANGLER: ") + hva

    /** R8 feiler ikke bygget når den stripper en komponent - appen blir bare stille borte. */
    private fun klassenFinnes() = runCatching { Class.forName(TJENESTE) }.isSuccess

    /**
     * Play setter seg selv som installer. En APK lagt inn med adb eller en filbehandler har
     * ingen, eller navnet på filbehandleren - og er dermed ikke en «trusted source» for verten.
     */
    @Suppress("DEPRECATION")
    private fun installertFra(pm: PackageManager, pakke: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(pakke).installingPackageName
        } else {
            pm.getInstallerPackageName(pakke)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun versjonAv(pm: PackageManager, pakke: String): String? =
        runCatching { pm.getPackageInfo(pakke, 0).versionName }.getOrNull()

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentServices(intent, 0)
        }
    }.getOrDefault(emptyList())
}
