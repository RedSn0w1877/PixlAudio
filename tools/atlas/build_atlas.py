#!/usr/bin/env python3
"""Builds genre-atlas-v1.json.gz from the checked-in sources (docs/brain-spec.md, 3.5). Offline and deterministic.

    python tools/atlas/build_atlas.py            # writes app/src/main/assets/genre-atlas-v1.json.gz and the sha file

Inputs
  sources/mb_genres.json        MusicBrainz genre list (CC0), from fetch_sources.py genres
  sources/mb_relations.json     MusicBrainz genre relationships (CC0), from fetch_sources.py relations (may be partial)
  sources/wikidata_genres.json  Wikidata labels, aliases, inception, country, parents, influences (CC0)
  sources/ids.tsv               the id registry: key -> atlas id. APPEND-ONLY. Stored tags keep their ids for ever, so a
                                new MusicBrainz genre gets the next free id and nothing already there ever moves.
  hand/*                        families, curated nodes, descriptors, source tables, extra aliases, the mood whitelist

Output record (one JSON object per genre in "g"; empty fields are left out):
  i id, m MusicBrainz mbid ("" for curated), w Wikidata id, n name, a aliases, p parent ids, inf influenced-by ids,
  fus fusion-of ids, rel related ids, fam family index, d depth below the family root, reg countries, era [inception year],
  x descriptor {e,v,dn,ac energy valence danceability acousticness 0..255, t [tempo lo, hi], mood [mood ids]}, desc paragraph
The file is one object (not a bare array as 3.5 says) because it also carries the 24 families, the mood whitelist and the
per-source tables the loader needs.
"""
import gzip, hashlib, io, json, os, sys, unicodedata
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", ".."))
SRC = os.path.join(HERE, "sources")
HAND = os.path.join(HERE, "hand")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "genre-atlas-v1.json.gz")
SHA_OUT = os.path.join(ROOT, "brain", "src", "test", "resources", "atlas", "genre-atlas-v1.sha256")
VERSION = 1

def load_json(name, default=None):
    p = os.path.join(SRC, name)
    if not os.path.exists(p): return default
    return json.load(open(p, encoding="utf-8"))

def rows(path):
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.strip() and not line.startswith("#"): yield line

def lname(s): return unicodedata.normalize("NFC", s).strip().lower()

def main():
    mb = load_json("mb_genres.json")
    rels = load_json("mb_relations.json", {})
    wd = load_json("wikidata_genres.json", {"genres": {}, "edges": []})
    wdg, wde = wd["genres"], wd["edges"]
    warnings = []

    # ---- nodes: every MusicBrainz genre, then the curated ones --------------------------------------------------
    nodes = {}  # key -> dict
    by_name = {}
    for g in mb:
        nodes[g["id"]] = {"key": g["id"], "m": g["id"], "n": g["name"], "a": [], "p": [], "inf": [], "fus": [], "rel": [], "reg": []}
        by_name.setdefault(lname(g["name"]), g["id"])
    curated = []
    for line in rows(os.path.join(HAND, "curated.tsv")):
        slug, name, parents, aliases, country, era = line.split("|")
        curated.append((slug, name, parents, aliases, country, era))
        nodes[slug] = {"key": slug, "m": "", "n": name, "a": [x for x in aliases.split(",") if x], "p": [], "inf": [], "fus": [], "rel": [],
                       "reg": [country] if country else [], "era": int(era) if era else 0}
        by_name[lname(name)] = slug

    def resolve(name):
        k = by_name.get(lname(name))
        if k is None: warnings.append("unknown genre name in a hand table: " + name)
        return k

    # ---- ids: append-only registry --------------------------------------------------------------------------------
    ids = {}
    reg_path = os.path.join(SRC, "ids.tsv")
    if os.path.exists(reg_path):
        for line in rows(reg_path):
            k, i = line.split("\t"); ids[k] = int(i)
    nxt = max(ids.values(), default=0) + 1
    for k in sorted(nodes):
        if k not in ids:
            ids[k] = nxt; nxt += 1
    with open(reg_path, "w", encoding="utf-8", newline="\n") as f:
        for k, i in sorted(ids.items(), key=lambda kv: kv[1]): f.write("%s\t%d\n" % (k, i))
    if nxt > 32000: raise SystemExit("atlas ids no longer fit an Int16")
    for k, n in nodes.items(): n["i"] = ids[k]

    # ---- edges ----------------------------------------------------------------------------------------------------------
    wd_sub, wd_inf = {}, {}
    for a, rel, b in wde:
        if a in nodes and b in nodes and a != b:
            (wd_sub if rel == "sub" else wd_inf if rel == "inf" else {}).setdefault(a, set()).add(b)
    for k, n in nodes.items():
        r = rels.get(k, {})
        sub = [x for x in r.get("sub", []) if x in nodes and x != k]
        if not sub: sub = sorted(wd_sub.get(k, []))
        inf = [x for x in r.get("inf", []) if x in nodes and x != k] or sorted(wd_inf.get(k, []))
        fus = [x for x in r.get("fus", []) if x in nodes and x != k]
        n["p"] = sorted(set(sub), key=lambda x: ids[x]); n["inf"] = sorted(set(inf), key=lambda x: ids[x]); n["fus"] = sorted(set(fus), key=lambda x: ids[x])
        w = wdg.get(k)
        if w:
            n["w"] = w["wd"]
            if w.get("year"): n["era"] = w["year"]
            n["reg"] = sorted(set(n["reg"]) | set(w.get("countries", [])))
            al = list(w.get("aliases", []))
            if w.get("label") and lname(w["label"]) != lname(n["n"]): al.append(w["label"])
            n["a"] = al + n["a"]
        elif k in rels or True:
            pass
        for area in rels.get(k, {}).get("area", []): pass
    for slug, name, parents, aliases, country, era in curated:
        n = nodes[slug]
        for p in parents.split(","):
            if p:
                pk = resolve(p)
                if pk and pk != slug: n["p"].append(pk)
        n["p"] = sorted(set(n["p"]), key=lambda x: ids[x])

    # ---- aliases: hand-written extras --------------------------------------------------------------------------------
    for line in rows(os.path.join(HAND, "aliases_extra.tsv")):
        name, al = line.split("|")
        k = resolve(name)
        if k: nodes[k]["a"] = nodes[k]["a"] + [x for x in al.split(",") if x]
    taken = {}
    for k in sorted(nodes, key=lambda x: ids[x]):
        n = nodes[k]; taken.setdefault(lname(n["n"]), k)
    for k in sorted(nodes, key=lambda x: ids[x]):
        n = nodes[k]; keep = []
        for a in n["a"]:
            la = lname(a)
            if not la or la == lname(n["n"]) or la in (lname(x) for x in keep): continue
            if taken.setdefault(la, k) != k: continue  # one name, one node: the first listing wins
            keep.append(a)
        n["a"] = keep

    # ---- cycles: drop a parent edge that closes a loop (deterministic: smallest ids first) --------------------------
    def reaches(start, target):
        seen, q = set(), deque([start])
        while q:
            x = q.popleft()
            if x == target: return True
            if x in seen: continue
            seen.add(x); q.extend(nodes[x]["p"])
        return False
    dropped = 0
    for k in sorted(nodes, key=lambda x: ids[x]):
        keep = []
        for p in nodes[k]["p"]:
            if reaches(p, k): dropped += 1
            else: keep.append(p)
        nodes[k]["p"] = keep
    if dropped: warnings.append("%d parent edges closed a cycle and were dropped" % dropped)

    # ---- families and depth ---------------------------------------------------------------------------------------------
    fam_names, fam_roots = [], []
    for line in rows(os.path.join(HAND, "families.tsv")):
        name, roots = line.split("|")
        fam_names.append(name)
        fam_roots.append([r for r in (resolve(x) for x in roots.split(",")) if r])
    root_of = {}
    for fi, rs in enumerate(fam_roots):
        for r in rs: root_of.setdefault(r, fi)

    def nearest_root(k, out_edges):
        best = None  # (dist, family)
        seen = {k: 0}; q = deque([k])
        while q:
            x = q.popleft(); d = seen[x]
            if x in root_of:
                cand = (d, root_of[x])
                if best is None or cand < best: best = cand
                continue
            if best is not None and d >= best[0]: continue
            for y in sorted(out_edges(x), key=lambda z: ids[z]):
                if y not in seen: seen[y] = d + 1; q.append(y)
        return best

    for k, n in nodes.items():
        b = nearest_root(k, lambda x: nodes[x]["p"])
        if b is None: b = nearest_root(k, lambda x: nodes[x]["p"] + nodes[x]["fus"] + nodes[x]["inf"])
        if b is not None: n["fam"], n["d"] = b[1], b[0]
        else: n["d"] = 1

    # ---- related: siblings in the same family that have a descriptor first, capped, plus nothing else ----------------
    children = {}
    for k, n in nodes.items():
        for p in n["p"]: children.setdefault(p, []).append(k)

    # ---- descriptors ----------------------------------------------------------------------------------------------------
    moods = [l for l in rows(os.path.join(HAND, "moods.txt"))]
    mood_id = {m: i for i, m in enumerate(moods)}
    own = {}
    for fn in sorted(os.listdir(HAND)):
        if not fn.startswith("descriptors"): continue
        for line in rows(os.path.join(HAND, fn)):
            name, e, v, dn, ac, tempo, mo, desc = line.split("|")
            k = resolve(name)
            if not k: continue
            lo, hi = tempo.split("-")
            ms = [mood_id[m] for m in mo.split(",") if m]
            for m in mo.split(","):
                if m not in mood_id: warnings.append("unknown mood %s in %s" % (m, name))
            if len(ms) > 4: warnings.append("more than 4 moods: " + name)
            own[k] = {"x": {"e": int(e), "v": int(v), "dn": int(dn), "ac": int(ac), "t": [int(lo), int(hi)], "mood": ms[:4]}, "desc": desc}
    for k, d in own.items():
        nodes[k]["x"] = d["x"]; nodes[k]["desc"] = d["desc"]
    inherited = 0
    for k in sorted(nodes, key=lambda x: ids[x]):
        if k in own: continue
        seen = {k}; q = deque([k]); found = None
        while q and found is None:
            x = q.popleft()
            for y in sorted(nodes[x]["p"], key=lambda z: ids[z]):
                if y in seen: continue
                seen.add(y)
                if y in own: found = y; break
                q.append(y)
        if found: nodes[k]["x"] = own[found]["x"]; inherited += 1

    for k, n in nodes.items():
        sibs = []
        for p in n["p"][:1]:
            sibs += [s for s in children.get(p, []) if s != k]
        sibs.sort(key=lambda s: (0 if s in own else 1, 0 if nodes[s].get("fam") == n.get("fam") else 1, ids[s]))
        n["rel"] = [ids[s] for s in sibs[:6]]

    # ---- source tables --------------------------------------------------------------------------------------------------
    src = {}
    for line in rows(os.path.join(HAND, "source_tables.tsv")):
        source, raw, target = line.split("|")
        if target == "-": src.setdefault(source, {})[raw] = []; continue
        ks = [resolve(t) for t in target.split(",")]
        src.setdefault(source, {})[raw] = [ids[x] for x in ks if x]

    # ---- write ---------------------------------------------------------------------------------------------------------
    recs = []
    for k in sorted(nodes, key=lambda x: ids[x]):
        n = nodes[k]; r = {"i": n["i"], "n": n["n"]}
        if n["m"]: r["m"] = n["m"]
        if n.get("w"): r["w"] = n["w"]
        if n["a"]: r["a"] = n["a"]
        if n["p"]: r["p"] = [ids[x] for x in n["p"]]
        if n["inf"]: r["inf"] = [ids[x] for x in n["inf"]]
        if n["fus"]: r["fus"] = [ids[x] for x in n["fus"]]
        if n["rel"]: r["rel"] = n["rel"]
        if "fam" in n: r["fam"] = n["fam"]
        r["d"] = n["d"]
        if n["reg"]: r["reg"] = n["reg"]
        if n.get("era"): r["era"] = [n["era"]]
        if n.get("x"): r["x"] = n["x"]
        if n.get("desc"): r["desc"] = n["desc"]
        recs.append(r)
    doc = {"v": VERSION, "fam": fam_names, "moods": moods, "src": src, "g": recs}
    raw = (json.dumps(doc, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    buf = io.BytesIO()
    with gzip.GzipFile(filename="", mode="wb", fileobj=buf, compresslevel=9, mtime=0) as gz: gz.write(raw)
    data = buf.getvalue()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    open(OUT, "wb").write(data)
    sha = hashlib.sha256(data).hexdigest()
    os.makedirs(os.path.dirname(SHA_OUT), exist_ok=True)
    open(SHA_OUT, "w", encoding="utf-8", newline="\n").write(sha + "  genre-atlas-v1.json.gz\n")

    fam_n = sum(1 for n in nodes.values() if "fam" in n)
    print("genres", len(nodes), "| with a parent", sum(1 for n in nodes.values() if n["p"]), "| with a family", fam_n,
          "| own descriptors", len(own), "| inherited", inherited, "| aliases", sum(len(n["a"]) for n in nodes.values()))
    print("raw bytes", len(raw), "gzip bytes", len(data), "sha256", sha)
    for w in sorted(set(warnings)): print("WARNING", w)

if __name__ == "__main__":
    main()
