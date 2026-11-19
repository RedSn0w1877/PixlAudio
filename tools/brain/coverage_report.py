#!/usr/bin/env python3
"""Stage 2b coverage probe, step 3: turns the probe's results into the coverage tables of docs/brain-coverage-2b.md.

    python tools/brain/coverage_report.py <tracks.jsonl> <results.json> <genre-atlas-v1.json.gz> [raw dir for chips]

Per source and combined, per language group: the share of the stand-in tracks with a tag vector (the 3.4 rule: some atlas node
reaches noisy-OR weight 0.25 before spreading), a genre, a year, a popularity, an energy and valence, a tempo. The Python
fold and noisy-OR below follow BrainText.fold and Tags.merge closely, not bit for bit; the Kotlin golden files pin the real ones.
Spotify's artist genres cannot be measured here (no account): they are left out and said so.
"""
import gzip, json, math, os, sys, unicodedata
from collections import defaultdict

def fold(s):
    s = s.replace("đ", "d").replace("Đ", "D").replace("ß", "ss").replace("æ", "ae").replace("ø", "o").replace("ł", "l")
    s = unicodedata.normalize("NFKD", s)
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    return " ".join("".join(c if (c.isalnum()) else " " for c in s).split())

TRUST = {1: 1.0, 2: 0.8, 3: 0.7, 4: 0.6, 6: 0.4, 8: 0.3}

def main(tracks_path, results_path, atlas_path, raw_dir=None):
    tracks = [json.loads(l) for l in open(tracks_path, encoding="utf8")]
    res = json.load(open(results_path, encoding="utf8"))
    atlas = json.loads(gzip.decompress(open(atlas_path, "rb").read()))
    by_mbid, by_name = {}, {}
    for g in atlas["g"]:
        if g.get("m"): by_mbid[g["m"]] = g
    for g in sorted(atlas["g"], key=lambda r: r["i"]):
        for n in [g["n"]] + g.get("a", []):
            by_name.setdefault(fold(n), g)
    src = {k: {fold(r): v for r, v in t.items()} for k, t in atlas["src"].items()}
    depth_boost = lambda g: min(2.4, 1 + 0.35 * g.get("d", 1))

    def place(label, mbid="", table=None):
        if mbid and mbid in by_mbid: return [by_mbid[mbid]["i"]]
        if table and table in src:
            hit = src[table].get(fold(label))
            if hit is not None: return hit
        g = by_name.get(fold(label))
        return [g["i"]] if g else []

    def merge(claims):
        """claims: {source: {id: conf}} -> (known, ids). Noisy-OR; known = some id reaches 0.25."""
        def noisy(sources):
            w = defaultdict(lambda: 1.0)
            for s, d in claims.items():
                if s not in sources: continue
                for i, c in d.items(): w[i] *= 1 - TRUST[s] * min(1.0, c)
            return {i: 1 - v for i, v in w.items()}
        w = noisy(set(TRUST))
        return (max(w.values(), default=0) >= 0.25), w

    def votes(items, key_label, key_votes, key_mbid=None, table=None):
        mx = max([x[key_votes] for x in items] + [0])
        out = {}
        for x in items:
            v = x[key_votes]
            if v <= 0 and mx > 0: continue
            conf = (v / mx) if mx > 0 and v > 0 else 1.0
            for i in place(x[key_label], x.get(key_mbid, "") if key_mbid else "", table):
                out[i] = max(out.get(i, 0), conf)
        return out

    dz, mb, it, lb, wd = res["deezer"], res["musicbrainz"], res["itunes"], res["listenbrainz"], res["wikidata"]
    rows = []
    for idx, t in enumerate(tracks):
        k = str(idx)
        d, m, i = dz.get(k, {}), mb.get(k, {}), it.get(k, {})
        l = lb.get(m.get("mbid")) if m.get("mbid") else None
        w = wd.get(m.get("artist_mbid")) if m.get("artist_mbid") else None
        row = {"group": t["group"], "src": t["src"].split("_")[0], "found": {}, "claims": defaultdict(dict), "per": {}}
        row["found"] = {"deezer": bool(d.get("found")), "mb": bool(m.get("found")), "itunes": bool(i.get("found")), "lb": bool(l), "wd": bool(w)}
        # Claims by the spec's source numbers.
        per = {}
        if l:
            rec = [x for x in l["tags"].get("recording", [])]
            a = votes(rec, "tag", "count", "genre_mbid")
            if a: row["claims"][1].update(a)
            per["lb_recording"] = bool(a)
            art = votes([x for x in l["tags"].get("artist", [])], "tag", "count", "genre_mbid")
            if art:
                for g_, c in art.items(): row["claims"][2][g_] = max(row["claims"][2].get(g_, 0), c)
            per["lb_artist"] = bool(art)
        if m.get("artist"):
            a = votes(m["artist"]["genres"], "name", "count", "mbid")
            for g_, c in a.items(): row["claims"][2][g_] = max(row["claims"][2].get(g_, 0), c)
            per["mb_artist"] = bool(a)
        if m.get("rec_genres"):
            a = votes(m["rec_genres"], "name", "count", "mbid")
            for g_, c in a.items(): row["claims"][1][g_] = max(row["claims"][1].get(g_, 0), c)
            per["mb_recording"] = bool(a)
        if w:
            ids = [by_mbid[x]["i"] for x in w["genre_mbids"] if x in by_mbid]
            for g_ in ids: row["claims"][2][g_] = max(row["claims"][2].get(g_, 0), 1.0)
            per["wikidata"] = bool(ids)
        if i.get("found") and i.get("genre"):
            ids = place(i["genre"], "", "itunes")
            for g_ in ids: row["claims"][6][g_] = 1.0
            per["itunes"] = bool(ids)
        if d.get("found") and d.get("genres"):
            ids = []
            for gname in d["genres"]: ids += place(gname, "", "deezer")
            for g_ in ids: row["claims"][8][g_] = 1.0
            per["deezer_album"] = bool(ids)
        row["per"] = per
        known, ws = merge(row["claims"])
        row["tags"] = known
        gby = {g["i"]: g for g in atlas["g"]}
        row["specific"] = known and any(v >= 0.25 and gby[gid].get("d", 1) >= 1 for gid, v in ws.items())
        row["vn"] = known and any(v >= 0.25 and (gby[gid]["n"] in ("v-pop", "vinahouse", "nhạc vàng", "vietnamese bolero") or gby[gid]["n"].startswith("vietnamese")) for gid, v in ws.items())
        # Each source alone.
        row["alone"] = {name: merge({s: dict(v)})[0] for name, s, v in [(n, s_, row["claims"].get(s_, {})) for n, s_ in (("recording genres (MB and LB)", 1), ("artist genres (MB, LB, Wikidata)", 2), ("iTunes genre", 6), ("Deezer album genre", 8))]}
        # Year and popularity.
        years = []
        def yr(s):
            return int(s[:4]) if s and len(s) >= 4 and s[:4].isdigit() and 1900 <= int(s[:4]) <= 2027 else 0
        if yr(m.get("first")): years.append(("mb", yr(m["first"])))
        if l and yr(l.get("first")): years.append(("mb", yr(l["first"])))
        if yr(i.get("release")): years.append(("itunes", yr(i["release"])))
        if t.get("year"): years.append(("youtube", t["year"]))
        if yr(d.get("release")): years.append(("deezer", yr(d["release"])))
        row["year_by"] = {s for s, _ in years}
        row["year"] = bool([1 for s, _ in years if s != "deezer"])  # never Deezer alone
        row["pop_deezer"] = bool(d.get("found") and (d.get("rank") or 0) > 0)
        row["pop_lb"] = bool(l and False)
        row["tempo"] = bool(d.get("bpm"))
        row["gain"] = d.get("gain") is not None and d.get("found")
        # Energy and valence need a tag with a descriptor.
        if known:
            top = sorted(ws.items(), key=lambda kv: -kv[1])
            row["feel"] = any("x" in next(g for g in atlas["g"] if g["i"] == gid) for gid, _ in top[:6])
        else:
            row["feel"] = False
        rows.append(row)

    groups = sorted({r["group"] for r in rows}, key=lambda g: -sum(1 for r in rows if r["group"] == g))
    pct = lambda n, d: ("%5.1f%%" % (100.0 * n / d)) if d else "   n/a"
    out = []
    P = out.append
    P("### Combined, per language group\n")
    P("| group | tracks | genre (tag vector) | a specific genre (below a family root) | year | popularity (Deezer) | energy and valence | tempo (Deezer) |")
    P("|---|---:|---:|---:|---:|---:|---:|---:|")
    for g in groups + ["all"]:
        rs = [r for r in rows if g == "all" or r["group"] == g]
        n = len(rs)
        P("| %s | %d | %s | %s | %s | %s | %s | %s |" % (g, n, pct(sum(r["tags"] for r in rs), n), pct(sum(r["specific"] for r in rs), n), pct(sum(r["year"] for r in rs), n), pct(sum(r["pop_deezer"] for r in rs), n), pct(sum(r["feel"] for r in rs), n), pct(sum(r["tempo"] for r in rs), n)))
    P("\n### Which source found the track at all (a hit that passes the title, artist and length checks)\n")
    P("| group | tracks | Deezer | MusicBrainz recording | ListenBrainz tags | Wikidata artist | iTunes |")
    P("|---|---:|---:|---:|---:|---:|---:|")
    for g in groups + ["all"]:
        rs = [r for r in rows if g == "all" or r["group"] == g]
        n = len(rs)
        P("| %s | %d | %s | %s | %s | %s | %s |" % (g, n, *[pct(sum(r["found"][k] for r in rs), n) for k in ("deezer", "mb", "lb", "wd", "itunes")]))
    P("\n### A genre from each source alone (maps to an atlas node and reaches the 0.25 floor)\n")
    names = list(rows[0]["alone"].keys())
    P("| group | tracks | " + " | ".join(names) + " |")
    P("|---|---:|" + "---:|" * len(names))
    for g in groups + ["all"]:
        rs = [r for r in rows if g == "all" or r["group"] == g]
        n = len(rs)
        P("| %s | %d | %s |" % (g, n, " | ".join(pct(sum(r["alone"][nm] for r in rs), n) for nm in names)))
    P("\n### Year, by source (a year from that source exists; the first row never counts Deezer alone)\n")
    P("| group | tracks | any believable year | MusicBrainz | iTunes | YouTube row | Deezer |")
    P("|---|---:|---:|---:|---:|---:|---:|")
    for g in groups + ["all"]:
        rs = [r for r in rows if g == "all" or r["group"] == g]
        n = len(rs)
        P("| %s | %d | %s | %s | %s | %s | %s |" % (g, n, pct(sum(r["year"] for r in rs), n), *[pct(sum(s in r["year_by"] for r in rs), n) for s in ("mb", "itunes", "youtube", "deezer")]))
    P("\n### Tag coverage by the radio the track came from (what kind of music the stand-in holds)\n")
    cats = defaultdict(list)
    for r in rows: cats[r["src"]].append(r)
    P("| source file group | tracks | genre |")
    P("|---|---:|---:|")
    for c, rs in sorted(cats.items(), key=lambda kv: -len(kv[1]))[:14]:
        P("| %s | %d | %s |" % (c, len(rs), pct(sum(r["tags"] for r in rs), len(rs))))
    vi = [r for r in rows if r["group"] == "vi"]
    P("\nVietnamese tracks with a Vietnamese-specific tag (v-pop, vinahouse, nhạc vàng, vietnamese ...): %d of %d (%s)." % (sum(r["vn"] for r in vi), len(vi), pct(sum(r["vn"] for r in vi), len(vi)).strip()))
    # What a fallback would have to cover.
    n = len(rows); untagged = [r for r in rows if not r["tags"]]
    P("\nTracks with no genre from any source: %d of %d (%s)." % (len(untagged), n, pct(len(untagged), n).strip()))
    print("\n".join(out))

if __name__ == "__main__":
    main(*sys.argv[1:5])
