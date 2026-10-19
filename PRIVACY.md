# Personvernerklæring for Botometer

Sist oppdatert: 19. oktober 2026

Botometer er en Android Auto-app som viser fart, gjeldende fartsgrense og hva farten ville kostet i
forenklet forelegg. Appen har ingen brukerkonto, ingen analyse, ingen reklame og ingen
krasjrapportering, og utvikleren har ingen server som mottar data fra appen.

## Hva appen bruker

**Posisjon.** Appen leser GPS-posisjon, fart og kurs mens den kjører, via Google Play-tjenestenes
posisjonstjeneste. Posisjonen brukes bare på telefonen, til å finne fartsgrensen for vegen du
kjører på. Den lagres ikke og sendes ikke videre som punkt. Et vedvarende varsel vises så lenge
posisjon leses.

**Bilens fart.** Hvis bilen tillater det, leses bilens rapporterte fart for å vise avviket mot
GPS-farten. Den forlater ikke telefonen.

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

Bare den sist nedlastede bøtesatstabellen, i appens private lagringsområde. Fartsgrensedata og
diagnostikk holdes i minnet og forsvinner når appen avsluttes. Avinstallering sletter alt.

## Tillatelser

- Posisjon (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`): fart og fartsgrense.
- Forgrunnstjeneste for posisjon: for å fortsette å lese posisjon når telefonskjermen er av.
  Appen ber ikke om posisjon i bakgrunnen.
- Varsler: for å vise at posisjon leses.
- Internett: NVDB og bøtesatser, som beskrevet over.
- Bilens fart (`CAR_SPEED`): valgfritt, se over.

## Barn

Appen er ment for førere av motorvogn og er ikke rettet mot barn.

## Endringer

Endringer i denne erklæringen publiseres i dette repoet, og historikken er synlig i git.

## Kontakt

Spørsmål kan stilles som en issue på https://github.com/henrik242/botometer/issues.
