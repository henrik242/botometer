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

Men sideloading er ikke veien rundt det, slik denne README-en tidligere hevdet. Android Autos
«Ukjente kilder» gjelder **ikke** apper bygget på Car App Library. Google sier det rett ut:

> Android Auto has a developer option that lets you run apps that aren't installed from a trusted
> source. This setting applies to media, messaging notifications, and parked apps but doesn't
> apply to apps built using the Android for Cars App Library.

En sidelastet templat-app dukker derfor **aldri** opp i en ekte bil, uansett hvor riktig
manifestet er - og verten sier ikke fra. Den utelater bare oppføringen. Det kostet en runde med
feilsøking i manifestet før noen leste testdokumentasjonen. Se «Hvordan appen faktisk kommer inn
i bilen» nedenfor.

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

**4. Bøtesatsene må tolkes, ikke parses.** Lovdata har et åpent datasett med gjeldende sentrale
forskrifter (NLOD 2.0, uten autentisering), så kilden *er* maskinlesbar. Men beløpene står som
løpende tekst i § 1, prikker og førerrett står i to andre forskrifter, og sikkerhetsfradraget
står ikke i noen forskrift i det hele tatt — det er påtalepraksis. En parser ville produsert
kronebeløp som er stille feil. Satsene ligger derfor som en versjonert asset i
`app/src/main/assets/botesatser.json`, med `versjon` synlig i UI og i unit-testene, vedlikeholdt
for hånd. Det som *er* automatisert er varselet: `.github/workflows/satser.yml` sammenlikner
endringsdatoene i Lovdatas datasett med `satser/kilder.json` hver mandag, og feiler når en av de
tre rettskildene er endret. Gjeldende satser er fra **15. februar 2026** (FOR-2026-02-06-147,
som endrer FOR-1990-06-29-492 § 1). Når satsene justeres — det skjer omtrent årlig — oppdaterer
du JSON-filen, forventningene i `FineCalculatorTest` og `sistEndret` i `satser/kilder.json`.

## Hvordan appen faktisk kommer inn i bilen

Verten krever at appen er installert fra en *trusted source*. I praksis betyr det Play, og
«Ukjente kilder» hjelper ikke for en templat-app. Det finnes likevel to veier inn som ikke
innebærer publisering eller review:

**Internal App Sharing** (`play.google.com/console/internalappsharing`) er den enkleste. Du laster
opp appen, får en lenke, åpner den på telefonen og installerer. Play står som installasjonskilde,
og appen dukker opp i bilen. **Ingen review, ingen Data Safety-erklæring, ingen publisering** - og
dermed heller ingen som vurderer om NAVIGATION-kategorien er berettiget. Det krever en
Play Console-konto (engangsavgift), men ikke en utgivelse.

Den tar imot **både APK og AAB** - hjelpesiden heter «Share app bundles and APKs internally». Blir
du bedt om en AAB, står du sannsynligvis på opplastingssiden til et utgivelsesspor og ikke på
Internal App Sharing. Signeringsnøkkelen spiller heller ingen rolle her: Play signerer opplastingen
om med sitt eget testsertifikat, så APK-en fra CI duger som den er, debug-nøkkel og alt.

Play avviser forresten en artefakt som hevder å være både Automotive OS-app og Android Auto-app:
«cannot declare `android.hardware.type.automotive` device feature and
`com.google.android.gms.car.application` metadata at the same time». Manifestet har derfor ingen
`<uses-feature>` for AOS - appen er projisert. Det oppdages først ved opplasting, altså etter et
grønt bygg, så `CarAppManifestTest` holder dem ute i stedet.

**Internt testspor** er alternativet om du vil ha flere enn deg selv på den, med opptil 100
testere og oppdateringer som er ute i løpet av minutter. Det sporet krever **AAB** - og en *fast*
keystore: CI signerer med en ny tilfeldig debug-nøkkel for hver kjøring når secretsene mangler, og
Play avviser andre opplasting fordi nøkkelen har byttet seg. Legg inn `BOTOMETER_KEYSTORE_BASE64`
m.m. som secrets før du går den veien.

**Desktop Head Unit** virker fortsatt med en lokalt installert APK, og er derfor stedet å utvikle:

```bash
$ANDROID_HOME/extras/google/auto/desktop-head-unit
```

Kravet om trusted source gjelder ekte biler. Det er DHU som gjør utviklingsløkka mulig i det hele
tatt - uten den måtte hver endring gjennom en opplasting.

**Det som ikke endrer seg med noen av dem:** template-restriksjonene håndheves av *bilverten* i
runtime, ikke av Play. Du får fortsatt bare tegne fritt inne i `NavigationTemplate`s surface.
`CAR_SPEED` er også vertsgatet, ikke distribusjonsgatet.

**Og targetSdk-jakten slipper du ikke unna.** Et utgivelsesspor krever gjeldende nivå - «must
target at least API level 36» - og en templat-app må gjennom Play for å vises i bilen i det hele
tatt. Antakelsen om at Play-fri distribusjon sparte oss for den holdt bare så lenge artefakten
aldri skulle den veien. `targetSdk` følger derfor Play, og oppgraderingen har konsekvenser å ta
med: fra 36 tegner appen alltid under status- og navigasjonsfeltet, og
`windowOptOutEdgeToEdgeEnforcement` er avviklet. `MainActivity` legger systemets innrykk oppå sitt
eget i stedet.

**Oppdateringskanalen for satsene, som fortsatt er det viktigste designvalget:** Internal App
Sharing er ingen oppdateringskanal - den varsler ikke, og den oppdaterer ikke noe av seg selv. En
APK bygget i 2026 ville kjørt 2026-satser i 2029 i stillhet, og satsene justeres omtrent årlig.
Derfor er satsene flyttet ut av APK-en:

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
crash-rapporter, så appen må rapportere seg selv. `Diagnostics` viser NVDB-kall, siste rute, siste
treff, siste bom og kandidatene matchingen forkastet — vinneren alene sier ikke hvorfor den vant.
`CrashLog` tar vare på siste stacktrace og viser den øverst: et krasj etterlot seg ellers
ingenting, og «den krasjer av og til» er ikke noe å feilsøke på. Teksten er selectable, så den kan
limes rett inn i en issue. Diagnostikken vises aldri i bilen — det er distraksjon uansett hvor
nyttig den er.

## Datakilder

| Data | Kilde | Lisens |
|---|---|---|
| Fartsgrenser | NVDB API LES v4, vegobjekttype 105, egenskap 2021 | NLOD 2.0 |
| Bøtesatser | Forskrift om forenklet forelegg (Lovdata, NLOD 2.0), manuelt vedlikeholdt, endringsvarsel i CI | — |
| Prikker / tap av førerrett | Prikkforskriften, tapsforskriften | — |

NVDB-kallet:

```
GET https://nvdbapiles.atlas.vegvesen.no/vegobjekter/api/v4/vegobjekter/105
  ?kartutsnitt={vest},{sør},{øst},{nord}
  &srid=4326
  &inkluder=egenskaper,geometri,lokasjon
  &segmentering=true
  &inkluderAntall=false
X-Client: botometer-android
```

Åpent, ingen nøkkel, men `X-Client` er påkrevd av Vegvesenet - uten den risikerer du struping.
Sett din egen verdi i `res/values/strings.xml`.

**v4 avviser ukjente parametre** med `400 Ugyldig forespørsel` i stedet for å ignorere dem. Kallet
bar lenge med seg `sortering=false` fra v3, og da feilet hvert eneste oppslag - permanent, siden
en 400 med rette ikke prøves på nytt. Slå opp nye parametre i v4-dokumentasjonen før du legger dem
til; `NvdbClientErrorTest` låser settet så en utvidelse må gjøres bevisst.

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

- **Fantomlinjer mellom delstrekninger er rettet**, men beslektede feil er lette å lage igjen:
  et vegobjekt med MULTILINESTRING-geometri er flere adskilte strekninger, og limes de sammen til
  én punktliste, får matchingen en rett linje mellom dem som ikke finnes i virkeligheten. Effekten
  var systematisk - 30-soner i tettsteder er mange korte strekninger, 80-veger er én lang - så
  fantomlinjene spente over hovedvegen og lot lav grense vinne. `MultiLineSegmentTest` holder det
  ute. Diagnostikken viser nå også kandidatene matchingen forkastet, ikke bare vinneren.
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

Testene krever **JDK 21 eller nyere**. Robolectric kjører dem mot `targetSdk`, og nekter SDK 36
på en eldre JDK. Bytekoden appen bygges til er fortsatt 17.

```bash
./gradlew :app:test              # satstabellen verifiseres
./gradlew :app:assembleRelease   # → app/build/outputs/apk/release/
./gradlew :app:bundleRelease     # → app/build/outputs/bundle/release/  (AAB, til Play)
adb install -r app/build/outputs/apk/release/botometer-*-release.apk   # nok for DHU, ikke bil
```

Filnavnene bærer kortshaen - `botometer-<kortsha>-release.apk` - av samme grunn som versionName
gjør det: en fil i nedlastingsmappa må kunne si hvilken commit den er uten at du installerer den
først.

Bruk `assembleRelease`, ikke `installDebug`: debug-bygg har `ALLOW_ALL_CAR_HOSTS = true`.

**Vil du ikke bygge selv:** `.github/workflows/apk.yml` bygger og tester på hver push, og
legger APK-en ved kjøringen som artefakt.

**For DHU** holder det å installere APK-en rett på telefonen. **For en ekte bil må den inn via
Play** - Internal App Sharing er nok, se «Hvordan appen faktisk kommer inn i bilen». En APK lagt
inn med `adb install` eller en filbehandler vises ikke i bilen, og du får ingen feilmelding som
sier hvorfor.

DHU gir ingen GPS-fart - bruk `adb emu geo fix` mot emulator, eller mock provider, for å teste
matchingen uten å kjøre bil.

### Dukker ikke appen opp i app-oversikten i bilen?

Åpne telefon-appen. Den øverste seksjonen kjører de samme spørringene mot PackageManager som
verten selv gjør, og leser dem fra den **installerte** APK-en. Teksten er selectable, så den kan
limes rett inn i en issue.

Rekkefølgen på sjekkene er svarene sortert etter hvor ofte de er årsaken:

1. **`✗ INSTALLERT UTENFOR PLAY`** - dette er nesten alltid det. «Ukjente kilder» gjelder ikke
   Car App Library-apper, og ingenting i manifestet kan rette på det. Legg den inn via Internal
   App Sharing.
2. **`✗ MANGLER: android:label` / `android:icon`** - attributtene må stå på `<service>`-en, ikke
   bare på `<application>`. Verten arver dem ikke, og uten dem har den ingenting å tegne en
   oppføring med. `CarAppManifestTest` skal fange dette før det rekker ut i en APK.
3. **`✗ CarAppService: IKKE synlig`** - manifestet eller R8. Linja under sier om klassen fortsatt
   finnes i APK-en.
4. **Alt `✓` og appen mangler likevel** - da er det verten, og bare vertsloggen sier hvorfor:

```bash
./tools/bil-diagnostikk.sh    # samme sjekker med adb, og til slutt vertsloggen
```

Gearhead logger hvorfor den forkaster en app - feil kategori, for høy `minCarApiLevel`, feilet
vertsvalidering. **Den loggen er svaret; den slår enhver hypotese i dette dokumentet.**

## Tre flater, og hvorfor det ikke er én

Bilverten kjører **én navigasjonsapp om gangen**. Starter Google Maps, kaller verten
`onStopNavigation` på oss. Car App Library har heller ingen liten sideflate å be om -
`NavigationTemplate` er fullskjerm, og panelet ved siden av Maps er vertens eget, forbeholdt
media og meldinger. Botometer kan altså eie bilskjermen, eller Maps kan. Ikke begge.

Derfor finnes appen på tre flater i stedet:

| Flate | Når | Hva den gir |
|---|---|---|
| **Bilskjermen** | Botometer er valgt i bilen | Speedometeret, tegnet på `NavigationTemplate` |
| **Telefonen** | Maps eier bilskjermen | Samme tall, stor skrift, skjermen holdes våken |
| **Varsel** | Alltid, i bakgrunnen | Heads-up når farten krysser inn i et nytt bøtenivå |

`LocationForegroundService` eier GPS-abonnementet og lever videre når Maps overtar skjermen, så
varslene virker uansett hvem som eier bilskjermen. Varselet er `IMPORTANCE_HIGH` og utvidet med
`CarAppExtender` - uten den vises ingen varsler på bilskjermen i det hele tatt.

**Varselet kommer bare ved overgang til et nytt nivå.** Et varsel per GPS-fix er ikke et varsel,
det er støy, og Google er tydelig på at heads-up er forbeholdt noe «drive-critical, time
sensitive, and actionable». At beløpet nettopp gikk fra 4 800 til 7 450 kroner er det. At du
fortsatt ligger 17 over er det ikke. Hysteresen som mangler (se «Kjente svakheter») er grovt
kompensert med et minste intervall mellom varsler, så en vipping på trinngrensa gir ett varsel
og ikke ti.

De tre flatene deler ett `SpeedLimitRepository`, opprettet i `BotometerApp`. Rute-cachen ligger i
instansen, så tre repoer ville betydd tre cacher og tre ganger så mange kall mot NVDB for de
samme rutene.

## Personvern

Posisjonen forlater aldri telefonen: NVDB-kallet sender en bbox på ~2 km, ikke et punkt. Det er en
reell designstyrke og bør ikke ofres for finere matching. `X-Client` er satt til `botometer-android`
uten versjon og personnavn, siden verdien havner i Vegvesenets logger.

Foreground-servicen viser et vedvarende varsel så lenge posisjon leses. Det er derfor
`POST_NOTIFICATIONS` etterspørres på API 33+ - servicen kjører uansett, men et skjult varsel er
dårlig gjennomsiktighet.

## Sikkerhet på skjermen

Renderingen er bevisst kjedelig: tre linjer, stor skrift, ingen animasjon, ingen interaksjon.
Fargen bæres av konsekvensen (grønn ingen bot → gul bot → oransje prikker → rød anmeldelse), slik
at informasjonen leses i periferien uten at blikket forlater veien.
