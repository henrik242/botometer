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

overskrift "5. Er Ukjente kilder skrudd på, og er Android Auto restartet etterpå?"
# Innstillingen leses ved oppstart av Android Auto. Skrur du den på uten å tvangsstoppe, er den
# ikke i effekt, og alt ser ut som om appen er avvist.
adb shell dumpsys package "$ANDROID_AUTO" | grep -E 'versionName' | head -1
echo "Tvangsstopper Android Auto, så innstillingen leses på nytt ..."
adb shell am force-stop "$ANDROID_AUTO"

overskrift "6. A/B: oppfører debug-bygget seg annerledes?"
# Debug-bygget skiller seg fra release på nøyaktig to ting: ingen R8, og ALLOW_ALL_CAR_HOSTS.
# Dukker debug opp mens release ikke gjør det, er det ett av de to - og da vet vi hvor vi skal
# lete i stedet for å gjette. Dukker ingen av dem opp, ligger årsaken hos verten, og steg 7 har
# svaret.
#
# Dette er en MÅLING, ikke en løsning. Debug-bygget skal ikke bli stående på telefonen: med
# ALLOW_ALL_CAR_HOSTS kan en vilkårlig app binde seg til CarAppService og lese posisjonen din.
# Avinstaller det når du er ferdig, og la release beholde vertsvalideringen.
cat <<'TIPS'
Kjør, i denne rekkefølgen:

    adb uninstall no.synth.botometer
    ./gradlew :app:installDebug
    # koble til, se etter appen i oversikten
    adb uninstall no.synth.botometer      # IKKE la debug-bygget bli stående

Dukket appen opp med debug, men ikke med release?  -> R8 eller vertsvalidering.
                                                      Steg 3 og 4 over skiller dem.
Dukket den ikke opp med noen av dem?               -> verten avviser appen. Steg 7.
TIPS

overskrift "7. Vertsloggen. DETTE ER SVARET."
echo "Loggen tømmes nå. Koble til bilen eller start Desktop Head Unit, og vent til"
echo "app-oversikten vises. Avslutt med Ctrl-C."
echo
echo "  \$ANDROID_HOME/extras/google/auto/desktop-head-unit"
echo
adb logcat -c
adb logcat | grep -iE "GH\.|Gearhead|CarApp|botometer|$PAKKE"
