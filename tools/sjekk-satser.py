#!/usr/bin/env python3
"""Sier fra når rettskildene bak botesatser.json er endret.

Satsene kan ikke hentes automatisk: beløpene står som løpende tekst i forskriften, prikker og
førerrett står i to andre forskrifter, og sikkerhetsfradraget står ikke i noen forskrift i det
hele tatt - det er påtalepraksis. Et parseresultat som er stille feil er verre enn ingen tall.

Det som derimot kan automatiseres er *varselet*: Lovdatas åpne datasett (NLOD 2.0) oppgir
`lastChangeInForce` per forskrift. Er den nyere enn det satser/kilder.json har notert, er
tabellen utdatert og må oppdateres for hånd.

Kjøres uten argumenter i CI. Lokalt kan et allerede nedlastet arkiv gjenbrukes:

    python3 tools/sjekk-satser.py --arkiv /tmp/gjeldende-sentrale-forskrifter.tar.bz2
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path

DATASETT = "https://api.lovdata.no/v1/publicData/get/gjeldende-sentrale-forskrifter.tar.bz2"

ROT = Path(__file__).resolve().parent.parent
KILDER = ROT / "satser" / "kilder.json"
SATSER = ROT / "satser" / "botesatser.json"


def hent_arkiv(mål: Path) -> Path:
    print(f"Laster ned {DATASETT}")
    with urllib.request.urlopen(DATASETT, timeout=300) as svar, open(mål, "wb") as fil:
        fil.write(svar.read())
    print(f"  {mål.stat().st_size / 1_000_000:.1f} MB")
    return mål


def les_felt(xml: str, klasse: str) -> str | None:
    """Metadatafeltene ligger som <dd class="...">-elementer i Lovdatas HTML-vedlegg."""
    treff = re.search(rf'<dd class="{klasse}">(.*?)</dd>', xml, re.S)
    return re.sub(r"<[^>]+>", "", treff.group(1)).strip() if treff else None


def sistEndret_fra_arkiv(arkiv: Path, dokumenter: set[str]) -> dict[str, str | None]:
    funnet: dict[str, str | None] = {}
    with tarfile.open(arkiv, "r:bz2") as tar:
        for medlem in tar:
            navn = Path(medlem.name).stem
            if navn not in dokumenter:
                continue
            fil = tar.extractfile(medlem)
            if fil is None:
                continue
            funnet[navn] = les_felt(fil.read().decode("utf-8", "replace"), "lastChangeInForce")
            if len(funnet) == len(dokumenter):
                break
    return funnet


def oppsummer(linjer: list[str]) -> None:
    """GitHub viser dette rett på kjøringen, så feilen er lesbar uten å åpne loggen."""
    sti = os.environ.get("GITHUB_STEP_SUMMARY")
    if sti:
        with open(sti, "a", encoding="utf-8") as fil:
            fil.write("\n".join(linjer) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--arkiv", type=Path, help="bruk et allerede nedlastet tar.bz2 i stedet for å laste ned")
    args = ap.parse_args()

    kilder = json.loads(KILDER.read_text(encoding="utf-8"))["kilder"]
    satser = json.loads(SATSER.read_text(encoding="utf-8"))

    with tempfile.TemporaryDirectory() as tmp:
        arkiv = args.arkiv or hent_arkiv(Path(tmp) / "forskrifter.tar.bz2")
        if not arkiv.exists():
            print(f"FEIL: fant ikke arkivet {arkiv}", file=sys.stderr)
            return 2
        live = sistEndret_fra_arkiv(arkiv, {k["dokument"] for k in kilder})

    avvik: list[str] = []
    tabell = ["| Forskrift | Notert | Hos Lovdata |", "| --- | --- | --- |"]

    for kilde in kilder:
        dok, notert = kilde["dokument"], kilde["sistEndret"]
        nå = live.get(dok)
        status = "=" if nå == notert else "ENDRET"
        if nå is None:
            status = "BORTE"
            avvik.append(f"{dok} ({kilde['tittel']}) finnes ikke lenger i datasettet")
        elif nå != notert:
            avvik.append(
                f"{kilde['tittel']} er endret: notert {notert}, hos Lovdata {nå}.\n"
                f"    Gir: {kilde['gir']}.\n"
                f"    https://lovdata.no/dokument/SF/forskrift/{dok[3:7]}-{dok[7:9]}-{dok[9:11]}-{int(dok[12:])}"
            )
        print(f"{status:7} {dok}  notert {notert}  hos Lovdata {nå}  ({kilde['tittel']})")
        tabell.append(f"| {kilde['tittel']} | {notert} | {nå} |")

        # Versjonsdatoen i tabellen skal følge forskriften som fastsetter beløpene.
        if kilde.get("styrerVersjon") and satser["versjon"] != notert:
            avvik.append(
                f"botesatser.json har versjon {satser['versjon']}, men kilder.json noterer "
                f"{notert} for {dok}. De skal være like."
            )

    if not avvik:
        print("\nAlle rettskilder er uendret. Satstabellen er fortsatt dekkende.")
        oppsummer(["## Satser: uendret", ""] + tabell)
        return 0

    print("\nRETTSKILDER ER ENDRET:\n", file=sys.stderr)
    for a in avvik:
        print(f"  - {a}", file=sys.stderr)
    print(
        "\nOppdater satser/botesatser.json (og asseten i app/src/main/assets/), "
        "forventningene i FineCalculatorTest, og sistEndret i satser/kilder.json.",
        file=sys.stderr,
    )
    oppsummer(
        ["## Satser må oppdateres", ""] + tabell + [""] + [f"- {a}" for a in avvik]
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
