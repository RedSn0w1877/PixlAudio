#!/usr/bin/env python3
"""Cuts the fixtures of the knowledge-source parser tests (docs/brain-spec.md, 10.4 "Parser fixtures") from the responses the
coverage probe cached (tools/brain/coverage_probe.py), plus a handful of live requests for famous public tracks, anonymous, with
a generic User-Agent, one request per second.

    python tools/brain/cut_knowledge_fixtures.py <probe cache dir> <out dir>

Everything is trimmed to a few rows; tokens in Deezer preview links are dropped; nothing about any listener is in a fixture.
"""
import glob, json, os, sys, time, urllib.parse, urllib.request

UA = "PixlAudio-coverage-probe/0.1 (anonymous; public tracks only; no account)"

def cached(cache, host, pred):
    for p in sorted(glob.glob(os.path.join(cache, host, "*.json"))):
        d = json.load(open(p, encoding="utf8"))
        if pred(d["url"], d["json"]): return d
    return None

def live(url, body=None):
    time.sleep(1.1)
    data = body.encode() if body else None
    h = {"User-Agent": UA, "Accept": "application/json"}
    if body: h["Content-Type"] = "application/json"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=h), timeout=40) as r:
            return json.loads(r.read().decode("utf-8", "replace"))
    except Exception as e:
        print("live failed", url, repr(e)[:120]); return None

def strip(o):
    if isinstance(o, dict):
        return {k: strip(v) for k, v in o.items() if k not in ("preview", "available_countries", "contributors", "md5_image", "picture", "picture_small", "picture_medium", "picture_big", "picture_xl", "cover", "cover_small", "cover_medium", "cover_big", "cover_xl", "tracklist", "share", "link")}
    if isinstance(o, list): return [strip(x) for x in o]
    return o

def write(out, name, obj):
    with open(os.path.join(out, name), "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1, sort_keys=True); f.write("\n")
    print("wrote", name, len(json.dumps(obj)))

def main(cache, out):
    os.makedirs(out, exist_ok=True)
    s = cached(cache, "deezer", lambda u, j: "search?q=The%20Weeknd%20Blinding" in u)
    if s:
        j = strip(s["json"]); j["data"] = j["data"][:2]; j.pop("total", None); j.pop("next", None)
        write(out, "deezer_search_blinding_lights.json", j)
    j = live("https://api.deezer.com/track/isrc:USUG11904206")
    if j: write(out, "deezer_track_blinding_lights.json", strip(j))
    if j and j.get("album"):
        a = live("https://api.deezer.com/album/%d" % j["album"]["id"])
        if a: write(out, "deezer_album_after_hours.json", strip(a))
    e = live("https://api.deezer.com/track/isrc:ZZZZZ0000000")
    if e: write(out, "deezer_error_not_found.json", e)
    write(out, "deezer_error_quota.json", {"error": {"type": "Exception", "message": "Quota limit exceeded", "code": 4}})

    m = cached(cache, "mb", lambda u, j: "recording?query" in u and "Blinding" in u)
    if m:
        j = m["json"]
        j["recordings"] = j["recordings"][:3]
        for r in j["recordings"]:
            r.pop("releases", None)
            for c in r.get("artist-credit", []):
                (c.get("artist") or {}).pop("aliases", None)
        write(out, "mb_recording_search_blinding_lights.json", j)
    isrc = live("https://musicbrainz.org/ws/2/isrc/USUG11904206?inc=artists&fmt=json")
    if isrc:
        isrc["recordings"] = isrc.get("recordings", [])[:3]
        for r in isrc["recordings"]:
            for c in r.get("artist-credit", []):
                (c.get("artist") or {}).pop("aliases", None)
        write(out, "mb_isrc_blinding_lights.json", isrc)
    a = live("https://musicbrainz.org/ws/2/artist/c8b03190-306c-4120-bb0b-6f2ebfc06ea9?inc=genres+tags&fmt=json")
    if a:
        for k in ("aliases", "life-span", "begin-area", "end-area", "isnis", "ipis"): a.pop(k, None)
        write(out, "mb_artist_the_weeknd.json", a)
    r = live("https://musicbrainz.org/ws/2/recording/1227659f-a933-4549-8fc4-d839b4cd3d45?inc=genres+tags&fmt=json")
    if r: write(out, "mb_recording_genres.json", r)

    lb = cached(cache, "lb", lambda u, j: "metadata/recording" in u and isinstance(j, dict) and any(v and v.get("tag", {}).get("recording") for v in j.values()))
    if lb is None: lb = cached(cache, "lb", lambda u, j: "metadata/recording" in u and isinstance(j, dict))
    if lb:
        j = lb["json"]
        keep = list(j.items())[:3]
        slim = {}
        for k, v in keep:
            slim[k] = {"artist": {"artists": [{"artist_mbid": x.get("artist_mbid"), "name": x.get("name"), "area": x.get("area")} for x in v["artist"]["artists"]]},
                       "recording": {"first_release_date": v["recording"].get("first_release_date"), "name": v["recording"].get("name")},
                       "release": {"year": v.get("release", {}).get("year")}, "tag": v.get("tag", {})}
        write(out, "lb_metadata_recordings.json", slim)
    pop = cached(cache, "lb", lambda u, j: "popularity" in u and isinstance(j, list) and any(x.get("total_user_count") for x in j))
    if pop: write(out, "lb_popularity.json", pop["json"][:4])
    else:
        write(out, "lb_popularity.json", [{"recording_mbid": "8bee2202-0b48-42be-9e89-bf68e6ab742c", "total_listen_count": None, "total_user_count": None}])

    wd = cached(cache, "wd", lambda u, j: isinstance(j, dict) and j.get("results", {}).get("bindings"))
    if wd:
        j = wd["json"]; j["results"]["bindings"] = j["results"]["bindings"][:3]
        write(out, "wikidata_artists.json", j)

    it = cached(cache, "itunes", lambda u, j: "Blinding" in u)
    if it:
        j = it["json"]; j["results"] = [{k: v for k, v in r.items() if k in ("wrapperType", "kind", "artistName", "trackName", "trackTimeMillis", "primaryGenreName", "releaseDate", "trackExplicitness", "collectionName")} for r in j["results"][:2]]
        j["resultCount"] = len(j["results"])
        write(out, "itunes_search_blinding_lights.json", j)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
