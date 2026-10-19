# Botometer

Android Auto-speedometer som viser hva farten koster: forenklet forelegg i kroner, prikker og
risiko for tap av førerrett, basert på gjeldende fartsgrense fra NVDB.

```
┌──────────────────────────────┐
│           100          ⃝80  │   fart (km/t) + skiltet fartsgrense
│           km/t               │
│                              │
│         7 450 kr             │   forenklet forelegg
│  20 km/t over · 2 prikker    │
│   EV6 S75D1 · satser 2026… │
└──────────────────────────────┘
```

## Bygg og installer

Testene krever JDK 21 eller nyere (Robolectric mot SDK 36).

```bash
./gradlew :app:test
./gradlew :app:assembleRelease   # APK, app/build/outputs/apk/release/
./gradlew :app:bundleRelease     # AAB til Play
```

Bruk release, ikke debug: debug-bygg har `ALLOW_ALL_CAR_HOSTS = true`, som lar en vilkårlig app
binde seg til `CarAppService` og lese posisjonen. CI (`.github/workflows/apk.yml`) bygger og
legger ved APK-en på hver push. Filnavnet bærer kortshaen.

**Appen må installeres fra Play for å vises i en ekte bil.** «Ukjente kilder» i Android Auto
gjelder ikke Car App Library-apper, og verten utelater en sidelastet app uten å si fra.

- **Internal App Sharing** er enklest: last opp APK eller AAB, åpne lenken på telefonen. Ingen
  review, og Play signerer om, så CI-APK-en duger.
- **Internt testspor** krever AAB og en fast keystore (`BOTOMETER_KEYSTORE_BASE64` m.m. som
  secrets i CI, eller `botometer.keystore` i `~/.gradle/gradle.properties` lokalt).
- **Desktop Head Unit** virker med en lokalt installert APK, og er stedet å utvikle:
  `$ANDROID_HOME/extras/google/auto/desktop-head-unit`. DHU gir ingen GPS-fart.

### Vises ikke appen i bilen?

Åpne telefon-appen. Diagnostikken kjører de samme PackageManager-sjekkene som verten:
installert utenfor Play (nesten alltid årsaken), manglende `android:label`/`android:icon` på
`<service>`, eller `CarAppService` fjernet av R8. Er alt grønt, les vertsloggen med
`./tools/bil-diagnostikk.sh` - den slår enhver hypotese.

## Designvalg

- **NAVIGATION-kategorien** er den eneste som gir fri tegneflate (`NavigationTemplate` +
  `SurfaceCallback`). Play godtar det ikke uten turn-by-turn, derfor Internal App Sharing.
- **Tre flater.** Bilen kjører én navigasjonsapp om gangen, så Botometer finnes på bilskjermen,
  på telefonen (når Maps eier bilskjermen) og som heads-up-varsel ved nytt bøtenivå. Alle tre
  leser fra ett delt `SpeedWatch` og ett `SpeedLimitRepository` i `BotometerApp`, og
  `ReadingText` eier teksten, så flatene alltid sier det samme.
- **Posisjon i bakgrunnen.** `LocationForegroundService` eier GPS-abonnementet. Fra Android 14 er
  «mens appen er i bruk» en forgrunnstillatelse, og telefon-appen er i bakgrunnen mens bilskjermen
  vises, så `ACCESS_BACKGROUND_LOCATION` trengs i tillegg. «Tillat alltid» finnes bare på
  innstillingssiden; appen viser en forklaring før den sender brukeren dit.
- **Sporingen telles, ikke knyttes til skjermer.** `Tracking` holder sporing så lenge noen trenger
  den: bil-økta, telefonskjermen eller selve bilforbindelsen (`CarConnection`). Ellers ville
  varslene dødd når Maps tok bilskjermen eller telefonskjermen slukket.
- **Av betyr av.** Hovedbryteren «Botometer er på» stopper sporing, speedometer, varsler og
  NVDB-oppslag, sjekket i `Tracking.acquire` og i tjenesten selv. «Avslutt» i bilen og to trykk
  på tilbake på telefonen stopper turen.
- **GPS-fart, ikke bilens.** `CAR_SPEED` er utilgjengelig på de fleste hovedenheter, og bilens
  speedometer viser bevisst for høyt (ECE R39). Skjermen viser faktisk fart og «X km/t over» ut
  fra den; sikkerhetsfradraget brukes bare til å velge sats.
- **Fartsgrenser i ruter.** NVDB hentes i ~2×2 km ruter med minnecache, forhåndslasting foran
  bilen, paginering og backoff med jitter. Matchingen bruker kursfilter om vegens akse, et
  matchevindu som vokser med GPS-usikkerheten, og demper skiltet når treffet er tvetydig.
  Motorvegstatus hentes bare i 90-soner og over.
- **Ingen hopping.** `FineHysteresis` slipper trinn opp med én gang og ned etter 1,5 s, og
  segmentvalget krever 8 m margin for å bytte. Uten GPS i 5 s sier skjermen fra; etter 20 s
  vises «--».
- **Manuell fartsgrense** kan settes på telefonen der NVDB mangler data, og nullstilles når
  turen er over.
- **Bøtesatser vedlikeholdes for hånd** i `satser/botesatser.json`. Lovdata-teksten er ikke trygt
  parsebar, og sikkerhetsfradraget er påtalepraksis. Appen henter nyeste versjon fra repoet, med
  APK-asseten som fallback, og flagger satser eldre enn 400 dager. `.github/workflows/satser.yml`
  feiler ukentlig hvis en rettskilde er endret. Ved satsendring: oppdater JSON-filen,
  `FineCalculatorTest` og `sistEndret` i `satser/kilder.json`.
- **Varsler i bilen** krever `CarAppExtender` og viktighet HIGH, og skal ikke ha
  `CATEGORY_NAVIGATION` (verten undertrykker dem da). «Send testvarsel» poster samme varsel på
  kommando.
- **Diagnostikk og krasjlogg** vises bare på telefonen og kan kopieres med «Kopier diagnostikk».
- **Bilskjermen er bevisst kjedelig:** tre linjer, stor skrift, ingen animasjon, dag- og
  nattpalett. Fargen viser konsekvensen (grønn → gul bot → oransje prikker → rød anmeldelse).

## Datakilder

| Data | Kilde | Lisens |
|---|---|---|
| Fartsgrenser | NVDB API LES v4, vegobjekttype 105 | NLOD 2.0 |
| Motorvegstatus | NVDB API LES v4, vegobjekttype 595 | NLOD 2.0 |
| Bøtesatser | Forskrift om forenklet forelegg (Lovdata) | NLOD 2.0 |
| Prikker / tap av førerrett | Prikkforskriften, tapsforskriften | NLOD 2.0 |

NVDB krever `X-Client`-header; sett din egen i `res/values/strings.xml`. v4 avviser ukjente
parametre med 400, og `NvdbClientErrorTest` låser parametersettet.

## Kjente svakheter

- Motorvegoppslaget antar et enum-format i NVDB som ikke er verifisert mot det ekte API-et.
- Ingen høyde i matchingen: bru og veg under matcher mot hverandre.
- Farge er eneste koding av alvorlighetsgrad.
- Prikker og førerrett er fra sekundærkilder, per hendelse, uten prøveperiode-regelen.
- Variable og midlertidige fartsgrenser finnes ikke i NVDB.
- Ruter lagres ikke over omstart.
- Backoff og negativ cache i `SpeedLimitRepository` er utestet.
- Svalbard forkastes av WKT-sjekken (breddegrad 57-72).
- Beløpene er anslag.

## Personvern og lisens

Se [PRIVACY.md](PRIVACY.md). Lisensiert under [Mozilla Public License 2.0](LICENSE).
