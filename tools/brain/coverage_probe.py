#!/usr/bin/env python3
"""Stage 2b coverage probe, step 2: run the candidate knowledge sources (spec 3.3) on the stand-in track set.

Anonymous: famous public tracks only, a generic User-Agent, no account, no listener data. One request per
second per host at most (MusicBrainz 1.1 s, iTunes 3.5 s, Wikidata 6 s, ListenBrainz 1 s, Deezer 1 s).
Every response is cached under <cache dir>, so a rerun repeats nothing. Hosts run in parallel threads.

usage: coverage_probe.py <tracks.jsonl> <cache dir> [limit]
"""
import hashlib, json, os, sys, threading, time, unicodedata, urllib.parse, urllib.request, urllib.error

UA = "PixlAudio-coverage-probe/0.1 (anonymous; public tracks only; no account)"

def fold(s):
    s = unicodedata.normalize("NFKD", s.replace("đ", "d").replace("Đ", "D"))
    s = "".join(c for c in s if not unicodedata.combining(c)).lower()
    return " ".join("".join(c if c.isalnum() else " " for c in s).split())

def clean_title(t):
    out, depth = [], 0
    for c in t:
        if c in "([": depth += 1
        elif c in ")]": depth = max(0, depth - 1)
        elif depth == 0: out.append(c)
    t = "".join(out)
    for sep in (" - ", " – ", " — "):
        if sep in t: t = t.split(sep)[0]
    return fold(t)

class Host:
    def __init__(self, name, interval, cache):
        self.name, self.interval, self.cache = name, interval, cache
        self.lock = threading.Lock(); self.last = 0.0; self.requests = 0; self.errors = 0; self.blocked = 0
        os.makedirs(os.path.join(cache, name), exist_ok=True)

    def _path(self, key):
        return os.path.join(self.cache, self.name, hashlib.sha1(key.encode()).hexdigest() + ".json")

    def fetch(self, url, body=None, headers=None, tries=4):
        key = url + ("|" + body if body else "")
        p = self._path(key)
        if os.path.exists(p):
            return json.load(open(p, encoding="utf8"))["json"]
        h = {"User-Agent": UA, "Accept": "application/json"}
        if headers: h.update(headers)
        data = None
        if body is not None:
            data = body.encode(); h["Content-Type"] = "application/json"
        for attempt in range(tries):
            with self.lock:
                wait = self.last + self.interval - time.time()
                if wait > 0: time.sleep(wait)
                self.last = time.time()
            self.requests += 1
            try:
                with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=40) as r:
                    txt = r.read().decode("utf-8", "replace")
                j = json.loads(txt)
                if self.name == "deezer" and isinstance(j, dict) and j.get("error", {}).get("code") == 4:
                    self.blocked += 1; time.sleep(5 * (attempt + 1)); continue
                json.dump({"url": url, "json": j}, open(p, "w", encoding="utf8"), ensure_ascii=False)
                return j
            except urllib.error.HTTPError as e:
                self.errors += 1
                if e.code in (429, 503, 502, 504):
                    time.sleep(min(60, (2 ** attempt) * 2 + (float(e.headers.get("Retry-After", 0)) if e.headers.get("Retry-After", "0").isdigit() else 0)))
                    continue
                if e.code in (400, 403, 404):
                    json.dump({"url": url, "json": {"_http": e.code}}, open(p, "w", encoding="utf8"))
                    return {"_http": e.code}
            except Exception:
                self.errors += 1; time.sleep(2 ** attempt)
        return None

def primary(t): return t["artists"][0]["name"]

def artist_match(want, got_names):
    w = fold(want)
    return any(fold(g) == w or w in fold(g) or fold(g) in w for g in got_names if fold(g))

def q(s): return urllib.parse.quote(s, safe="")

def run_deezer(tracks, host, out):
    for i, t in enumerate(tracks):
        rec = {}
        j = host.fetch("https://api.deezer.com/search?q=" + q(primary(t) + " " + t["title"]) + "&limit=5")
        hit = None
        for d in (j or {}).get("data", []) or []:
            if clean_title(d.get("title", "")) == clean_title(t["title"]) and artist_match(primary(t), [d.get("artist", {}).get("name", "")]) \
               and (not t["dur"] or abs(d.get("duration", 0) - t["dur"]) <= 3):
                hit = d; break
        if hit:
            full = host.fetch("https://api.deezer.com/track/%d" % hit["id"]) or {}
            rec = {"id": hit["id"], "isrc": full.get("isrc"), "rank": full.get("rank", hit.get("rank")), "release": full.get("release_date"),
                   "bpm": full.get("bpm"), "gain": full.get("gain"), "album": (hit.get("album") or {}).get("id"), "artist": (hit.get("artist") or {}).get("id")}
            if rec["album"]:
                al = host.fetch("https://api.deezer.com/album/%d" % rec["album"]) or {}
                rec["genres"] = [g["name"] for g in (al.get("genres") or {}).get("data", [])]
        out[i] = {"found": bool(hit), **rec}
        if i % 50 == 0: print("deezer", i, host.requests, host.errors, host.blocked, flush=True)

def run_mb(tracks, host, out, done):
    artists = {}
    for i, t in enumerate(tracks):
        qs = 'recording:"%s" AND artist:"%s"' % (t["title"].replace('"', ""), primary(t).replace('"', ""))
        j = host.fetch("https://musicbrainz.org/ws/2/recording?query=" + q(qs) + "&fmt=json&limit=5")
        hit = None
        for r in (j or {}).get("recordings", []) or []:
            names = [c.get("name", "") for c in r.get("artist-credit", [])] + [c.get("artist", {}).get("name", "") for c in r.get("artist-credit", [])]
            if r.get("score", 0) >= 85 and clean_title(r.get("title", "")) == clean_title(t["title"]) and artist_match(primary(t), names) \
               and not (r.get("disambiguation") and any(w in r["disambiguation"].lower() for w in ("live", "remix", "instrumental", "karaoke"))):
                hit = r; break
        rec = {"found": bool(hit)}
        if hit:
            ac = [c for c in hit.get("artist-credit", []) if c.get("artist")]
            amb = ac[0]["artist"]["id"] if ac else None
            rec.update({"mbid": hit["id"], "artist_mbid": amb, "search_tags": [x["name"] for x in hit.get("tags", [])],
                        "first": hit.get("first-release-date"), "isrcs": hit.get("isrcs", [])})
            lk = host.fetch("https://musicbrainz.org/ws/2/recording/%s?inc=genres+tags&fmt=json" % hit["id"]) or {}
            rec["rec_genres"] = [{"name": g["name"], "mbid": g["id"], "count": g.get("count", 0)} for g in lk.get("genres", [])]
            rec["rec_tags"] = [{"name": g["name"], "count": g.get("count", 0)} for g in lk.get("tags", [])]
            if amb and amb not in artists:
                al = host.fetch("https://musicbrainz.org/ws/2/artist/%s?inc=genres+tags&fmt=json" % amb) or {}
                artists[amb] = {"genres": [{"name": g["name"], "mbid": g["id"], "count": g.get("count", 0)} for g in al.get("genres", [])],
                                "tags": [{"name": g["name"], "count": g.get("count", 0)} for g in al.get("tags", [])],
                                "country": al.get("country")}
            rec["artist"] = artists.get(amb)
        out[i] = rec
        if i % 50 == 0: print("mb", i, host.requests, host.errors, flush=True)
    done.set()

def run_itunes(tracks, host, out):
    for i, t in enumerate(tracks):
        j = host.fetch("https://itunes.apple.com/search?term=" + q(primary(t) + " " + t["title"]) + "&entity=song&limit=5")
        hit = None
        for d in (j or {}).get("results", []) or []:
            if clean_title(d.get("trackName", "")) == clean_title(t["title"]) and artist_match(primary(t), [d.get("artistName", "")]) \
               and (not t["dur"] or abs(d.get("trackTimeMillis", 0) / 1000 - t["dur"]) <= 3):
                hit = d; break
        out[i] = {"found": bool(hit), "genre": hit.get("primaryGenreName") if hit else None, "release": (hit or {}).get("releaseDate"),
                  "explicit": (hit or {}).get("trackExplicitness")}
        if i % 50 == 0: print("itunes", i, host.requests, host.errors, flush=True)

def run_after_mb(tracks, mb_out, done, lbh, wdh, lb_out, wd_out):
    done.wait()
    mbids = sorted({r["mbid"] for r in mb_out.values() if r.get("mbid")})
    for k in range(0, len(mbids), 16):
        chunk = mbids[k:k + 16]
        j = lbh.fetch("https://api.listenbrainz.org/1/metadata/recording/", body=json.dumps({"recording_mbids": chunk, "inc": "artist tag release"}))
        for m in chunk:
            e = (j or {}).get(m)
            lb_out[m] = None if not e else {"tags": {k2: [{"tag": x["tag"], "count": x.get("count", 0), "genre_mbid": x.get("genre_mbid")} for x in v]
                                                      for k2, v in (e.get("tag") or {}).items()},
                                              "year": (e.get("release") or {}).get("year"), "first": (e.get("recording") or {}).get("first_release_date")}
        pj = lbh.fetch("https://api.listenbrainz.org/1/popularity/recording", body=json.dumps({"recording_mbids": chunk}))
        for e in pj if isinstance(pj, list) else []:
            if e.get("recording_mbid") in lb_out and lb_out[e["recording_mbid"]] is not None:
                lb_out[e["recording_mbid"]]["listens"] = e.get("total_listen_count"); lb_out[e["recording_mbid"]]["users"] = e.get("total_user_count")
        print("lb", k, len(mbids), flush=True)
    amb = sorted({r["artist_mbid"] for r in mb_out.values() if r.get("artist_mbid")})
    for k in range(0, len(amb), 100):
        chunk = amb[k:k + 100]
        vals = " ".join('"%s"' % m for m in chunk)
        sp = ('SELECT ?mbid ?a (GROUP_CONCAT(DISTINCT ?gm; separator="|") AS ?gmbids) (GROUP_CONCAT(DISTINCT ?gl; separator="|") AS ?glabels) '
              'WHERE { VALUES ?mbid { %s } ?a wdt:P434 ?mbid . OPTIONAL { { ?a wdt:P136 ?g } UNION { ?a wdt:P8052 ?g } ?g wdt:P8052 ?gm . ?g rdfs:label ?gl FILTER(LANG(?gl)="en") } } GROUP BY ?mbid ?a' % vals)
        j = wdh.fetch("https://query.wikidata.org/sparql?format=json&query=" + q(sp), headers={"Accept": "application/sparql-results+json"})
        for b in ((j or {}).get("results", {}) or {}).get("bindings", []):
            wd_out[b["mbid"]["value"]] = {"wd": b["a"]["value"].rsplit("/", 1)[-1], "genre_mbids": [x for x in b.get("gmbids", {}).get("value", "").split("|") if x],
                                          "genres": [x for x in b.get("glabels", {}).get("value", "").split("|") if x]}
        print("wd", k, len(amb), flush=True)

def main():
    tracks = [json.loads(l) for l in open(sys.argv[1], encoding="utf8")]
    cache = sys.argv[2]
    if len(sys.argv) > 3: tracks = tracks[:int(sys.argv[3])]
    os.makedirs(cache, exist_ok=True)
    hosts = {n: Host(n, iv, cache) for n, iv in (("deezer", 1.0), ("mb", 1.1), ("itunes", 3.5), ("lb", 1.0), ("wd", 6.0))}
    dz, mb, it, lb, wd = {}, {}, {}, {}, {}
    done = threading.Event()
    ths = [threading.Thread(target=run_deezer, args=(tracks, hosts["deezer"], dz)),
           threading.Thread(target=run_mb, args=(tracks, hosts["mb"], mb, done)),
           threading.Thread(target=run_itunes, args=(tracks, hosts["itunes"], it)),
           threading.Thread(target=run_after_mb, args=(tracks, mb, done, hosts["lb"], hosts["wd"], lb, wd))]
    for t in ths: t.start()
    for t in ths: t.join()
    stats = {n: {"requests": h.requests, "errors": h.errors, "blocked": h.blocked} for n, h in hosts.items()}
    json.dump({"tracks": len(tracks), "deezer": dz, "musicbrainz": mb, "itunes": it, "listenbrainz": lb, "wikidata": wd, "hosts": stats},
              open(os.path.join(cache, "results.json"), "w", encoding="utf8"), ensure_ascii=False)
    print("done", stats)

if __name__ == "__main__":
    main()
