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

## Fire ting du bør vite før du bygger

**1. Appen distribueres ikke via Google Play, og er bygget for det.** Car App Library har faste
kategorier (navigation, parking, charging, POI, IoT, media, messaging). Det finnes ingen
«dashboard»-kategori, og bare navigasjonsapper får en fri tegneflate (`NavigationTemplate` +
`SurfaceCallback`) — alle andre templates er ferdigdefinerte lister du ikke kan tegne et
speedometer i. Appen deklarerer seg derfor som `androidx.car.app.category.NAVIGATION`, noe som
ikke ville passert Play-review siden kategorien krever faktisk turn-by-turn-navigasjon.

Sideloading er altså en forutsetning, ikke en nødløsning. Se «Hva sideloading faktisk endrer»
nedenfor for hva det gir og hva det ikke gir.

**2. GPS-abonnementet eies av en foreground service.** Fra Android 10 får en app ikke posisjon når
den ikke er i forgrunnen, og en `CarAppService` gir ikke forgrunnsstatus - appen ville fått fart de
første sekundene og deretter stillstand så snart telefonskjermen slukket. `LocationForegroundService`
(type `location`) eier abonnementet og publiserer til `SpeedFeed`; skjermen leser derfra. Det er
alternativet til `ACCESS_BACKGROUND_LOCATION`, som er strengere regulert i Play og unødvendig her.

**3. Farten kommer fra GPS, ikke fra bilen.** `CarInfo.addSpeedListener` finnes, men krever
`com.google.android.gms.permission.CAR_SPEED` og returnerer `STATUS_UNAVAILABLE` på de fleste
hovedenheter. GPS er dessuten *riktigere* for formålet: bilens speedometer viser bevisst litt for
høyt (ECE R39 tillater overrapportering, aldri underrapportering), mens politiet måler den faktiske
farten. `CarSpeedSource` er med, men bare for å kunne vise avviket.

**4. Bøtesatsene har ingen API.** Lovdata har ingen åpen API for forskriftstekst (Lovdata Pro er
kommersiell), og satsene ligger uansett i en tabell som må tolkes, ikke parses. De ligger derfor
som en versjonert asset i `app/src/main/assets/botesatser.json`, med `versjon` synlig i UI og i
unit-testene. Gjeldende satser er fra **15. februar 2026** (FOR-2026-02-06-147, som endrer
FOR-1990-06-29-492 § 1). Når satsene justeres — det skjer omtrent årlig — oppdaterer du JSON-filen
og forventningene i `FineCalculatorTest`.

## Hva sideloading faktisk endrer

**Det som åpner seg:** NAVIGATION-kategorien uten turn-by-turn er greit. Ingen review, ingen
Data Safety-erklæring, ingen tvungen targetSdk-jakt. `ACCESS_BACKGROUND_LOCATION` ville også vært
mulig (Play krever begrunnelsesvideo), men foreground service er fortsatt riktigere design, så det
er ikke brukt.

**Det som ikke endrer seg:** template-restriksjonene håndheves av *bilverten* i runtime, ikke av
Play. Du får fortsatt bare tegne fritt inne i `NavigationTemplate`s surface. `CAR_SPEED` er også
vertsgatet, ikke distribusjonsgatet. Ingenting av det som var teknisk blokkert løsner.

**Den nye risikoen, og det viktigste designvalget i denne runden:** Play var oppdateringskanalen
for bøtesatsene. En APK bygget i 2026 ville kjørt 2026-satser i 2029 i stillhet, og satsene
justeres omtrent årlig. Derfor er satsene flyttet ut av APK-en:

- `FineTableRepository` laster fra `satser/botesatser.json` i repoet, med APK-asseten som fallback
- Nyeste `versjon` vinner, så en fersk installasjon overstyrer en gammel cache
- JSON-en parses *før* den skrives til disk, ellers ville en ødelagt respons overlevd restart
- Over 400 dager gamle satser flagges både i telefon-appen og i footeren på bilskjermen
- Utdaterte satser hentes automatisk når telefon-appen åpnes; knappen er der for å tvinge en oppdatering

Et tall som er feil uten å se feil ut er verre enn ingen tall.

**Signering.** Har du egen keystore, legg inn `botometer.keystore` m.m. i
`~/.gradle/gradle.properties`. Uten dem signeres release med debug-nøkkelen: installerbar, men
debug-nøkkelen er offentlig kjent, så signaturen beviser ingenting om opphav. God nok til å
installere selv, ikke god nok til å dele videre. `ALLOW_ALL_CAR_HOSTS` er `false` i alle
release-bygg uansett nøkkel — uten vertsvalidering kan en vilkårlig app på telefonen binde seg til
`CarAppService` og lese posisjonen din, og en sideloadet APK har ingen Play-signatur som gjenstår
som kontroll.

**Telefon-appen er nå oppsett, oppdateringskanal og diagnostikk.** Uten Play Console finnes ingen
crash-rapporter, så `Diagnostics` viser NVDB-kall, siste rute, siste treff og siste feil. Teksten
er selectable, så den kan limes rett inn i en issue. Diagnostikken vises aldri i bilen — det er
distraksjon uansett hvor nyttig den er.

## Datakilder

| Data | Kilde | Lisens |
|---|---|---|
| Fartsgrenser | NVDB API LES v4, vegobjekttype 105, egenskap 2021 | NLOD 2.0 |
| Bøtesatser | Forskrift om forenklet forelegg (Lovdata), manuelt vedlikeholdt | — |
| Prikker / tap av førerrett | Prikkforskriften, tapsforskriften | — |

NVDB-kallet:

```
GET https://nvdbapiles.atlas.vegvesen.no/vegobjekter/api/v4/vegobjekter/105
  ?kartutsnitt={vest},{sør},{øst},{nord}
  &srid=4326
  &inkluder=egenskaper,geometri,lokasjon
  &segmentering=true
  &sortering=false&inkluderAntall=false
X-Client: botometer-android/0.1
```

Åpent, ingen nøkkel, men `X-Client` er påkrevd av Vegvesenet — uten den risikerer du struping.
Sett din egen verdi i `res/values/strings.xml`.

## Arkitektur

```
LocationForegroundService ─→ GpsSpeedSource ─→ SpeedFeed (StateFlow) ─┐
                                                                      ├─→ SpeedometerScreen ─→ SpeedometerRenderer (Canvas på Surface)
SpeedLimitRepository ──LimitMatch─────────────────────────────────────┘         │
    ├── NvdbClient (OkHttp, paginert, typede feil)                              └─→ FineCalculator ─→ FineTable (assets)
    ├── LruCache<Tile, List<Segment>>        (positiv cache)
    └── HashMap<Tile, Failure>               (negativ cache + backoff)
```

**Hvorfor tile-cache og ikke ett kall per posisjon:** NVDB-kall tar hundrevis av millisekunder, og
mobildekning i norske dalfører og tunneler er upålitelig. Repoet laster ~2×2 km ruter, cacher dem i
minnet, og forhåndslaster ruta ~60 sekunder foran bilen ut fra kurs og fart. Faller nettet ut,
vises siste sikre treff dempet (`stale`) i stedet for «ukjent».

**Hvorfor kart-matching med kursfilter:** nærmeste linjestykke alene plukker lett en parallell
lokalveg eller en avkjøringsrampe med annen fartsgrense. `SpeedLimitRepository` avviser segmenter
som avviker mer enn 45° fra GPS-kursen (og dropper filteret under 8 km/t, der kursen fra GPS er
støy).

**Hvorfor negativ cache:** `ensureTile` hoppet tidligere bare av hvis ruta var cachet eller
underveis. Feilet kallet, var den ingen av dem - og neste GPS-fix prøvde igjen. I en tunnel med
1 Hz ble det 60 forespørsler i minuttet mot et offentlig API. Nå får hver rute eksponentiell
backoff med jitter (2 s → 5 min), og `NvdbException.retryable` skiller transiente feil fra 400-er
som aldri blir bedre. Jitteren hindrer at alle ruter som feilet i samme tunnel prøver igjen i
samme sekund når dekningen kommer tilbake.

**Hvorfor paginering:** `metadata.neste.start` følges til den er tom. Uten det mistet vi
fartsgrenser stille i tette byruter, nettopp der de varierer mest. Vi bygger URL-en selv i stedet
for å følge `neste.href`, siden href-en historisk har pekt på gamle stier uten `/api/v4/`. Treffer
vi sidegrensen, settes `TileData.complete = false` og det logges - ufullstendige data skal ikke se
ut som «her finnes det ingen fartsgrense».

**Hvorfor sikkerhetsfradrag:** politiet trekker fra i målt fart før overtredelsen fastsettes —
i praksis 3 km/t opp til 100 km/t, 3 % over. Uten det ville appen systematisk overdrive boten i
grenseland. Fradraget ligger i `Tolerance` i satsfilen, ikke i koden.

## Kjente svakheter

Etter review-runden står følgende igjen som uløst. Det viktigste først:

- **Hysterese mangler**, både på trinnvalg og segmentvalg. Beløpet vil flimre rundt en trinngrense,
  og fartsgrensen kan hoppe mellom to nesten like gode segmenter i kryss. Dette er det neste som
  bør fikses - det er tallet som trekker blikket.
- **Ingen høydeinformasjon i matchingen.** Bru over veg og tunnel under veg matcher mot hverandre.
  NVDB leverer Z i WKT-en; `Wkt.parsePoint` kaster den i dag.
- **Farge er eneste koding** av alvorlighetsgrad, som utelukker rød-grønn fargeblindhet.
- **Prikker og tap av førerrett** er hentet fra sekundærkilder, ikke fra prikkforskriften og
  tapsforskriften direkte. Prikkmodellen er dessuten per hendelse, ikke kumulativ, og mangler
  doblingsregelen for førerkort i prøveperioden.
- **«X km/t til første bot»** optimaliserer mot bøtegrensen, ikke mot bremselengde. Vurder å bytte
  linjen mot stopplengde ved gjeldende fart.
- **`SpeedLimitRepository` har ingen tester.** `now`-parameteren er injiserbar for formålet, men
  `NvdbClient` må bak et interface før backoff-logikken kan testes.
- **Wkt-sanity-sjekken bruker breddegrad 57-72**, så Svalbard forkastes stille.

### Datakvalitet

- **Variable fartsgrenser** (tunneler, motorvei med skiltstyring) ligger ikke i NVDB som gjeldende
  verdi. Appen viser den skiltede statiske grensen og tar feil der.
- **Midlertidig skilting** ved veiarbeid finnes ikke i NVDB i sanntid.
- **Motorveg-flagget** er ikke slått opp. Satsen for 36–40 km/t over i 90-sone eller høyere gjelder
  bare motorveg; appen markerer den som «usikker». Fiks: slå opp riktig vegobjekttype for motorveg
  i datakatalogen (`/vegobjekttyper`) og legg den inn som en parallell tile-oppslag.
- **Ingen persistering av tiles** over app-restart. Room eller en enkel fil-cache er neste steg om
  du vil ha det til å fungere offline i kjente områder.
- Beløpene er **anslag**. Politiet kan velge vanlig forelegg også i lavere sjikt, og gjentakelse
  påvirker prikker og førerrett.

## Bygg og installer

```bash
./gradlew :app:test              # satstabellen verifiseres
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/
adb install -r app/build/outputs/apk/release/app-release.apk
```

Bruk `assembleRelease`, ikke `installDebug`: debug-bygg har `ALLOW_ALL_CAR_HOSTS = true`.

**Vil du ikke bygge selv:** `.github/workflows/apk.yml` bygger og tester på hver push, og
legger APK-en ved kjøringen som artefakt. Uten distribusjonskanal er CI kanalen.

**Skru på sideloading i Android Auto:** Innstillinger → trykk «Versjon» ti ganger →
utviklerinnstillinger → *Ukjente kilder*. Appen dukker opp i bilen, eller i Desktop Head Unit:

```bash
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

DHU gir ingen GPS-fart. Bruk `adb emu geo fix` mot emulator, eller mock provider, for å teste
matchingen uten å kjøre bil.

## Personvern

Posisjonen forlater aldri telefonen: NVDB-kallet sender en bbox på ~2 km, ikke et punkt. Det er en
reell designstyrke og bør ikke ofres for finere matching. `X-Client` er satt til `botometer-android/0.1`
uten personnavn, siden verdien havner i Vegvesenets logger.

Foreground-servicen viser et vedvarende varsel så lenge posisjon leses. Det er derfor
`POST_NOTIFICATIONS` etterspørres på API 33+ - servicen kjører uansett, men et skjult varsel er
dårlig gjennomsiktighet.

## Sikkerhet på skjermen

Renderingen er bevisst kjedelig: tre linjer, stor skrift, ingen animasjon, ingen interaksjon.
Fargen bæres av konsekvensen (grønn ingen bot → gul bot → oransje prikker → rød anmeldelse), slik
at informasjonen leses i periferien uten at blikket forlater veien.
