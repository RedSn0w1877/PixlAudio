#!/usr/bin/env python3
"""Re-runs the Wikidata step of the coverage probe with POST (the GET form of coverage_probe.py is too long for the endpoint
when 100 ids are listed, and every request failed). Reads results.json, asks for the artists' genres 100 ids at a time, one
query per 6 s, and writes them back into results.json under "wikidata". Anonymous; MusicBrainz artist ids only.

    python tools/brain/coverage_wikidata.py <cache dir>
"""
import json, os, sys, time, urllib.parse, urllib.request

UA = "PixlAudio-coverage-probe/0.1 (anonymous; public tracks only; no account)"

def main(cache):
    path = os.path.join(cache, "results.json")
    res = json.load(open(path, encoding="utf8"))
    ids = sorted({r["artist_mbid"] for r in res["musicbrainz"].values() if r.get("artist_mbid")})
    out, requests, errors = {}, 0, 0
    for k in range(0, len(ids), 100):
        chunk = ids[k:k + 100]
        values = " ".join('"%s"' % m for m in chunk)
        q = ('SELECT ?mbid ?a (GROUP_CONCAT(DISTINCT ?gm; separator="|") AS ?gmbids) (GROUP_CONCAT(DISTINCT ?gl; separator="|") AS ?glabels) '
             'WHERE { VALUES ?mbid { %s } ?a wdt:P434 ?mbid . OPTIONAL { ?a wdt:P136 ?g . ?g wdt:P8052 ?gm . ?g rdfs:label ?gl FILTER(LANG(?gl)="en") } } GROUP BY ?mbid ?a' % values)
        req = urllib.request.Request("https://query.wikidata.org/sparql", data=urllib.parse.urlencode({"query": q}).encode(),
                                     headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
        for attempt in range(3):
            requests += 1
            try:
                with urllib.request.urlopen(req, timeout=120) as r:
                    j = json.loads(r.read().decode("utf-8"))
                for b in j["results"]["bindings"]:
                    out[b["mbid"]["value"]] = {"wd": b["a"]["value"].rsplit("/", 1)[-1],
                                               "genre_mbids": [x for x in b.get("gmbids", {}).get("value", "").split("|") if x],
                                               "genres": [x for x in b.get("glabels", {}).get("value", "").split("|") if x]}
                break
            except Exception as e:
                errors += 1; print("retry", repr(e)[:100], flush=True); time.sleep(10)
        time.sleep(6)
        print("wd", k, len(ids), len(out), flush=True)
    res["wikidata"] = out
    res["hosts"]["wd"] = {"requests": requests, "errors": errors, "blocked": 0}
    json.dump(res, open(path, "w", encoding="utf8"), ensure_ascii=False)

if __name__ == "__main__":
    main(sys.argv[1])
