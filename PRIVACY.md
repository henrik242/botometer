# Personvernerklæring for Botometer

Sist oppdatert: 19. oktober 2026

Botometer er en Android Auto-app som viser fart, gjeldende fartsgrense og hva farten ville kostet i
forenklet forelegg. Appen har ingen brukerkonto, ingen analyse, ingen reklame og ingen
krasjrapportering, og utvikleren har ingen server som mottar data fra appen.

## Hva appen bruker

**Posisjon.** Appen leser GPS-posisjon, fart og kurs via Google Play-tjenestenes
posisjonstjeneste, til å vise fart, fartsgrense og bøtenivå og til å varsle når farten krysser
inn i et nytt bøtenivå. Posisjonen brukes bare på telefonen, lagres ikke og sendes ikke videre som
punkt.

Posisjon leses mens Botometer vises på bilskjermen eller telefonen, og mens telefonen er koblet
til bilen med Android Auto. Det skjer også når appen er lukket eller ikke i bruk på telefonen,
fordi telefon-appen ligger i bakgrunnen mens bilskjermen vises. Et vedvarende varsel vises så
lenge posisjon leses. Hovedbryteren «Botometer er på» stopper all posisjonslesing, og «Avslutt»
i bilen eller to trykk på tilbake på telefonen stopper den for turen.

## Hva som sendes over nett

**Statens vegvesen (NVDB).** For å hente fartsgrenser sender appen forespørsler til
`nvdbapiles.atlas.vegvesen.no`. Hver forespørsel inneholder et rektangel på noen få kilometer i et
fast rutenett, ikke din nøyaktige posisjon. Appen henter også ruta foran deg ut fra kurs og fart.
Forespørslene er merket med `X-Client: botometer-android`, uten versjon, enhets-ID eller
personopplysninger. Vegvesenet ser som for alle nettforespørsler din IP-adresse, og rekkefølgen av
ruter kan over tid gi et grovt bilde av hvor du har kjørt. Se Statens vegvesens egen
personvernerklæring for hvordan de behandler logger.

**GitHub.** Når telefon-appen åpnes og satsene er utdaterte, eller når du trykker «Oppdater
satser», lastes bøtesatsene ned fra `raw.githubusercontent.com`. Forespørselen inneholder ingen
posisjon eller personopplysninger, men GitHub ser IP-adressen din. Se GitHubs personvernerklæring.

**Google.** Posisjonstjenesten i Google Play-tjenester og Android Auto er levert av Google og
reguleres av Googles egne vilkår, ikke av denne erklæringen.

## Hva som lagres på telefonen

I appens private lagringsområde ligger:

- den sist nedlastede bøtesatstabellen
- innstillingene (fire av/på-verdier)
- siste krasj, hvis appen har krasjet: tidspunkt, appversjon og teknisk feilmelding, uten posisjon.
  Den kan slettes fra telefon-appen.

Fartsgrensedata, manuell fartsgrense og diagnostikk holdes i minnet og forsvinner når appen
avsluttes. Diagnostikken kan inneholde vegnummer og omtrentlig område for siste treff, og forlater
bare telefonen hvis du selv trykker «Kopier diagnostikk» og limer den inn et sted. Avinstallering
sletter alt.

## Tillatelser

- Posisjon (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`): fart og fartsgrense.
- Posisjon i bakgrunnen (`ACCESS_BACKGROUND_LOCATION`, «Tillat alltid»): for at bilskjermen og
  fartsvarslene skal virke mens telefon-appen ikke er åpen. Appen forklarer dette før du sendes
  til innstillingen, og virker på telefonen uten den.
- Forgrunnstjeneste for posisjon: for å fortsette å lese posisjon når telefonskjermen er av, med
  et synlig varsel.
- Varsler: for varselet om at posisjon leses, og for fartsvarsler på telefonen og bilskjermen.
- Internett: NVDB og bøtesatser, som beskrevet over.

## Barn

Appen er ment for førere av motorvogn og er ikke rettet mot barn.

## Endringer

Endringer i denne erklæringen publiseres i dette repoet, og historikken er synlig i git.

## Kontakt

Spørsmål kan stilles som en issue på https://github.com/henrik242/botometer/issues.
