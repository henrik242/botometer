# Botometer

Android Auto-speedometer som viser hva farten koster: forenklet forelegg i kroner, prikker og
risiko for tap av førerrett, basert på gjeldende fartsgrense fra NVDB.

```
┌──────────────────────────────┐
│            97          ⃝80  │   fart (km/t) + skiltet fartsgrense
│           km/t               │
│                              │
│         7 450 kr             │   forenklet forelegg
│  17 km/t over · 2 prikker    │
│   EV6 S75D1 · satser 2026… │
└──────────────────────────────┘
```

## Bygg og installer

```bash
./gradlew :app:test
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Bruk `assembleRelease`, ikke `installDebug`: debug-bygg har `ALLOW_ALL_CAR_HOSTS = true`, som lar
en vilkårlig app på telefonen binde seg til `CarAppService` og lese posisjonen. CI
(`.github/workflows/apk.yml`) bygger og legger ved APK-en på hver push.

Uten egen keystore i `~/.gradle/gradle.properties` signeres release med den offentlig kjente
debug-nøkkelen. Greit for eget bruk, ikke for å dele videre.

**Sideloading i Android Auto:** Innstillinger → trykk «Versjon» ti ganger →
utviklerinnstillinger → *Ukjente kilder*. For test uten bil, bruk Desktop Head Unit
(`$ANDROID_HOME/extras/google/auto/desktop-head-unit`) og `adb emu geo fix` for posisjon.

## Designvalg

- **Ikke på Google Play.** Bare NAVIGATION-kategorien gir en fri tegneflate i Car App Library, og
  den krever turn-by-turn-navigasjon for å passere review. Template-restriksjonene håndheves
  likevel av bilverten, så sideloading løsner ingenting teknisk.
- **GPS-fart, ikke bilens.** `CAR_SPEED` er utilgjengelig på de fleste hovedenheter, og bilens
  speedometer viser bevisst for høyt (ECE R39). Bilens fart vises bare som avvik når den finnes.
- **Foreground service for posisjon** (`LocationForegroundService`), i stedet for
  `ACCESS_BACKGROUND_LOCATION`. Ellers stopper posisjonen når telefonskjermen slukkes.
- **Fartsgrenser i ruter.** NVDB hentes i ~2×2 km ruter med minnecache, forhåndslasting av ruta
  foran bilen, paginering, og eksponentiell backoff med jitter ved feil. Matchingen avviser
  segmenter som avviker mer enn 45° fra kursen. Uten nett vises siste treff dempet.
- **Bøtesatser vedlikeholdes for hånd** i `satser/botesatser.json`. Lovdata-teksten er ikke
  trygt parsebar, og sikkerhetsfradraget er påtalepraksis, ikke forskrift. Appen henter nyeste
  versjon fra repoet, med APK-asseten som fallback, og flagger satser eldre enn 400 dager.
  `.github/workflows/satser.yml` feiler ukentlig hvis en av rettskildene er endret.
  Ved satsendring: oppdater JSON-filen, `FineCalculatorTest` og `sistEndret` i
  `satser/kilder.json`.
- **Telefon-appen viser diagnostikk** (NVDB-kall, siste treff og feil), siden det ikke finnes
  crash-rapporter utenfor Play. Aldri i bilen.
- **Bilskjermen er bevisst kjedelig:** tre linjer, stor skrift, ingen animasjon. Fargen viser
  konsekvensen (grønn → gul bot → oransje prikker → rød anmeldelse).

## Datakilder

| Data | Kilde | Lisens |
|---|---|---|
| Fartsgrenser | NVDB API LES v4, vegobjekttype 105 | NLOD 2.0 |
| Bøtesatser | Forskrift om forenklet forelegg (Lovdata) | NLOD 2.0 |
| Prikker / tap av førerrett | Prikkforskriften, tapsforskriften | NLOD 2.0 |

NVDB krever `X-Client`-header. Sett din egen verdi i `res/values/strings.xml`.

## Kjente svakheter

- Ingen hysterese: beløp og fartsgrense kan flimre rundt trinngrenser og i kryss.
- Ingen høyde i matchingen: bru og veg under matcher mot hverandre.
- Farge er eneste koding av alvorlighetsgrad.
- Prikker og førerrett er fra sekundærkilder, per hendelse, uten prøveperiode-regelen.
- Motorveg-flagget slås ikke opp; satsen for 36-40 km/t over i 90+ merkes «usikker».
- Variable og midlertidige fartsgrenser finnes ikke i NVDB.
- Ruter lagres ikke over omstart.
- `SpeedLimitRepository` mangler tester.
- Svalbard forkastes av WKT-sjekken (breddegrad 57-72).
- Beløpene er anslag.

## Personvern og lisens

Se [PRIVACY.md](PRIVACY.md). Lisensiert under [Mozilla Public License 2.0](LICENSE).
