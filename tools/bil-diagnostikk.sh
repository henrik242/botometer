#!/usr/bin/env bash
#
# Samler alt som trengs for å avgjøre HVORFOR Botometer ikke dukker opp i app-oversikten i bilen.
#
# Rekkefølgen er ikke tilfeldig. Hvert steg utelukker et lag, og du skal lese dem ovenfra og ned:
# er ikke tjenesten installert, er det ingen vits i å lese vertsloggen; svarer PackageManager på
# spørringen, er manifestet i orden og svaret ligger i loggen fra verten.
#
#   ./tools/bil-diagnostikk.sh
#
# Krever adb på PATH og telefonen tilkoblet med USB-debugging.

set -uo pipefail

PAKKE="no.synth.botometer"
TJENESTE="$PAKKE/.car.BotometerCarAppService"
ANDROID_AUTO="com.google.android.projection.gearhead"

# Loggen kan inneholde posisjon og enhets-ID-er. Si fra før noen limer den inn i en issue.
echo "MERK: utdata kan inneholde enhetsidentifikatorer. Se over før du deler den."
echo

if ! command -v adb >/dev/null; then
    echo "FEIL: adb finnes ikke på PATH."
    exit 1
fi

if [ -z "$(adb devices | sed -n '2p')" ]; then
    echo "FEIL: ingen enhet tilkoblet. Sjekk USB-debugging."
    exit 1
fi

overskrift() { echo; echo "=== $* ==="; }

overskrift "0. Hvor er appen installert fra? Dette avgjør alt annet."
# «Ukjente kilder» i Android Auto gjelder media-, meldings- og parkerte apper, IKKE apper bygget
# på Car App Library. Google sier det rett ut i testdokumentasjonen. En sidelastet templat-app
# dukker derfor aldri opp i en ekte bil, uansett hvor riktig manifestet er - og verten sier ikke
# fra. Den utelater bare oppføringen. Alt under dette steget er bortkastet om dette er svaret.
KILDE=$(adb shell dumpsys package "$PAKKE" | grep -m1 installerPackageName | tr -d '\r')
echo "${KILDE:-  (ingen installerPackageName - lagt inn med adb eller filbehandler)}"
if echo "$KILDE" | grep -q com.android.vending; then
    echo "==> Installert fra Play. Går videre."
else
    echo "==> IKKE installert fra Play. Dette er grunnen, og manifestet kan ikke rette på det."
    echo "    Legg den inn via Play Internal App Sharing - ingen review, ingen publisering."
    echo "    Desktop Head Unit virker fortsatt med en lokalt installert APK."
fi

overskrift "1. Er appen installert, og henger det igjen en gammel fartsbot?"
# To apper som deklarerer samme CarAppService-action gir forvirrende oppførsel i launcheren.
adb shell pm list packages | grep -iE 'botometer|fartsbot' || echo "INGEN TREFF - appen er ikke installert."

overskrift "2. Hvilken build er faktisk installert?"
# versionCode er antall commits. Er den lavere enn `git rev-list --count HEAD`, tester du en
# gammel APK, og alt under er målt på feil kode.
adb shell dumpsys package "$PAKKE" | grep -E 'versionCode|versionName' | head -2
echo "Til sammenlikning, dette treet: versionCode=$(git rev-list --count HEAD 2>/dev/null || echo '?')"

overskrift "3. Overlevde CarAppService R8?"
adb shell dumpsys package "$PAKKE" | grep -i -A6 carappservice || echo "IKKE FUNNET - se steg 4."

overskrift "4. Løser PackageManager opp tjenesten? (samme spørring verten gjør)"
SVAR=$(adb shell pm query-services -a androidx.car.app.CarAppService 2>&1)
echo "$SVAR"
if echo "$SVAR" | grep -qi botometer; then
    echo
    echo "==> Tjenesten ER synlig for PackageManager. Manifestet er altså i orden, og verten"
    echo "    avviser appen av en annen grunn. Steg 6 har svaret."
else
    echo
    echo "==> Tjenesten er IKKE synlig. Da er det manifestet eller R8, ikke verten."
    echo "    Sjekk at <service> har action androidx.car.app.CarAppService og at den er exported."
fi

overskrift "5. Hvilken Android Auto-versjon, og restart av verten"
# NB: «Ukjente kilder» hjelper IKKE for denne appen - se steg 0. Innstillingen gjelder media-,
# meldings- og parkerte apper. Vertsversjonen er likevel verdt å ha med, siden den avgjør hvilke
# Car App API-nivåer som støttes, og en restart rydder bort en cachet app-liste.
adb shell dumpsys package "$ANDROID_AUTO" | grep -E 'versionName' | head -1
echo "Tvangsstopper Android Auto, så innstillingen leses på nytt ..."
adb shell am force-stop "$ANDROID_AUTO"

overskrift "6. Vertsloggen. SVARET, hvis steg 0 til 5 var i orden."
echo "Loggen tømmes nå. Koble til bilen eller start Desktop Head Unit, og vent til"
echo "app-oversikten vises. Avslutt med Ctrl-C."
echo
echo "  \$ANDROID_HOME/extras/google/auto/desktop-head-unit"
echo
adb logcat -c
adb logcat | grep -iE "GH\.|Gearhead|CarApp|botometer|$PAKKE"
