#!/usr/bin/env python3
"""Fetches the CC0 inputs of the genre atlas (docs/brain-spec.md, 3.5, build recipe steps 1 to 3).

    python tools/atlas/fetch_sources.py genres      # MusicBrainz genre list, 23 requests, 1 per 1.1 s
    python tools/atlas/fetch_sources.py wikidata    # labels, aliases, inception, country, parents, influences (3 SPARQL queries)
    python tools/atlas/fetch_sources.py relations   # MusicBrainz genre pages, one per genre, 1 per 1.2 s, resumable (~45 min)

Output goes to tools/atlas/sources/ and is checked in, so build_atlas.py runs offline and reproducibly.
Anonymous requests with a generic User-Agent; nothing about any listener. MusicBrainz genre data and
Wikidata are CC0, so the atlas may bundle them (MusicBrainz tag votes are NOT CC0 and are never fetched here).
"""
import html, json, os, re, sys, time, urllib.parse, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "sources")
UA = "PixlAudio-atlas-build/1.0 (offline build script; public genre data only)"

def get(url, headers=None, tries=5, accept="application/json", timeout=90):
    h = {"User-Agent": UA, "Accept": accept}
    if headers: h.update(headers)
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=timeout) as r:
                return r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503, 504):
                wait = e.headers.get("Retry-After", "")
                time.sleep(float(wait) if wait.isdigit() else 3 * (attempt + 1) ** 2)
                continue
            raise
        except Exception:
            time.sleep(3 * (attempt + 1))
    raise RuntimeError("giving up on " + url)

def write(name, obj):
    with open(os.path.join(SRC, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        f.write("\n")

def genres():
    out, offset = [], 0
    while True:
        j = json.loads(get("https://musicbrainz.org/ws/2/genre/all?fmt=json&limit=100&offset=%d" % offset))
        out += [{"id": g["id"], "name": g["name"]} for g in j["genres"]]
        offset += 100
        print("genres", len(out), "of", j.get("genre-count"), flush=True)
        if offset >= j.get("genre-count", 0): break
        time.sleep(1.1)
    out.sort(key=lambda g: g["id"])
    write("mb_genres.json", out)

CORE = """SELECT ?g ?mb ?gLabel (GROUP_CONCAT(DISTINCT ?alias; separator="|") AS ?aliases) (MIN(YEAR(?inc)) AS ?year) (GROUP_CONCAT(DISTINCT ?cc; separator="|") AS ?countries) WHERE {
 ?g wdt:P8052 ?mb . OPTIONAL { ?g skos:altLabel ?alias FILTER(LANG(?alias)="en") } OPTIONAL { ?g wdt:P571 ?inc } OPTIONAL { ?g wdt:P495 ?c . ?c wdt:P297 ?cc }
 SERVICE wikibase:label { bd:serviceParam wikibase:language "en". } } GROUP BY ?g ?mb ?gLabel"""
EDGES = """SELECT ?mb ?rel ?mb2 WHERE { ?g wdt:P8052 ?mb . { ?g wdt:P279 ?o BIND("sub" AS ?rel) } UNION { ?g wdt:P737 ?o BIND("inf" AS ?rel) } UNION { ?g wdt:P144 ?o BIND("based" AS ?rel) } UNION { ?g wdt:P1269 ?o BIND("facet" AS ?rel) } ?o wdt:P8052 ?mb2 }"""

def sparql(q):
    data = urllib.parse.urlencode({"query": q}).encode()
    req = urllib.request.Request("https://query.wikidata.org/sparql", data=data,
                                 headers={"User-Agent": UA, "Accept": "application/sparql-results+json"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=240) as r:
                txt = r.read().decode("utf-8", "replace")
            return [{k: v["value"] for k, v in b.items()} for b in json.loads(txt)["results"]["bindings"]]
        except Exception as e:
            print("sparql retry", attempt, repr(e)[:200], flush=True)
            time.sleep(10 * (attempt + 1))
    raise RuntimeError("wikidata gave up")

def wikidata():
    core = sparql(CORE); time.sleep(6)
    edges = sparql(EDGES)
    out = {}
    for c in core:
        out[c["mb"]] = {"wd": c["g"].rsplit("/", 1)[1], "label": c.get("gLabel", ""),
                        "aliases": sorted({a for a in c.get("aliases", "").split("|") if a}),
                        "year": int(c["year"]) if c.get("year") else 0,
                        "countries": sorted({x for x in c.get("countries", "").split("|") if x})}
    ed = sorted({(e["mb"], e["rel"], e["mb2"]) for e in edges})
    write("wikidata_genres.json", {"genres": out, "edges": [list(e) for e in ed]})
    print("wikidata", len(out), "genres", len(ed), "edges")

REL_NAMES = {"subgenre of": "sub", "fusion of": "fus", "influenced by": "inf", "from": "area"}

def parse_page(t):
    i = t.find(">Relationships<")
    if i < 0: return {}
    j = t.find(">External links<", i)
    seg = t[i:j] if j > 0 else t[i:i + 20000]
    out = {}
    for th, td in re.findall(r"<th>([^<]*)</th><td[^>]*>(.*?)</td>", seg, flags=re.S):
        key = REL_NAMES.get(html.unescape(th).strip(": ").lower())
        if not key: continue
        if key == "area":
            out[key] = sorted(set(html.unescape(x) for x in re.findall(r'href="/area/[0-9a-f-]{36}"[^>]*><bdi>([^<]+)', td)))
        else:
            out[key] = sorted(set(re.findall(r'href="/genre/([0-9a-f-]{36})"', td)))
    return out

def relations():
    gs = json.load(open(os.path.join(SRC, "mb_genres.json"), encoding="utf-8"))
    path = os.path.join(SRC, "mb_relations.json")
    done = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {}
    todo = [g["id"] for g in gs if g["id"] not in done]
    print(len(done), "done,", len(todo), "to go", flush=True)
    for n, mbid in enumerate(todo):
        try:
            done[mbid] = parse_page(get("https://musicbrainz.org/genre/" + mbid, accept="text/html"))
        except Exception as e:
            print("skip", mbid, repr(e)); continue
        if n % 50 == 0:
            write("mb_relations.json", done); print(n, "of", len(todo), flush=True)
        time.sleep(1.2)
    write("mb_relations.json", done)

if __name__ == "__main__":
    os.makedirs(SRC, exist_ok=True)
    {"genres": genres, "wikidata": wikidata, "relations": relations}[sys.argv[1]]()
