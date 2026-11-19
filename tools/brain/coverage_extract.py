#!/usr/bin/env python3
"""Stage 2b coverage probe, step 1: the stand-in track set.

Reads the saved YouTube Music responses (builds/music-brain-2026-11-17/yt/raw) and writes
one JSON line per distinct (artist, title): artist names and channel ids, title, album, year, duration,
explicit flag, music video type, and the source file group. Offline: no network.

usage: coverage_extract.py <raw dir> <out.jsonl>
"""
import json, os, sys, unicodedata

VI = set("ăâđêôơưĂÂĐÊÔƠƯạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ"
         "ẠẢẤẦẨẪẬẮẰẲẴẶẸẺẼẾỀỂỄỆỈỊỌỎỐỒỔỖỘỚỜỞỠỢỤỦỨỪỬỮỰỲỴỶỸ")

def walk(o, key, out):
    if isinstance(o, dict):
        for k, v in o.items():
            if k == key: out.append(v)
            else: walk(v, key, out)
    elif isinstance(o, list):
        for v in o: walk(v, key, out)

def script_group(text):
    hangul = kana = cjk = vi = 0
    for ch in text:
        c = ord(ch)
        if 0xAC00 <= c <= 0xD7AF or 0x1100 <= c <= 0x11FF or 0x3130 <= c <= 0x318F: hangul += 1
        elif 0x3040 <= c <= 0x30FF: kana += 1
        elif 0x4E00 <= c <= 0x9FFF: cjk += 1
        elif ch in VI: vi += 1
    if hangul: return "ko"
    if kana: return "ja"
    if cjk: return "zh"
    if vi: return "vi"
    return ""

SEED_GROUP = {  # radios and searches whose seed fixes the main language group
    "radio_vpop": "vi", "next_edge": "", "radio_kpop": "ko",
}

def file_category(name):
    n = name.lower()
    for k, g in (("vpop", "vi"), ("sontung", "vi"), ("kpop", "ko"), ("newjeans", "ko")):
        if k in n: return g
    return ""

def runs_text(o):
    if not o: return ""
    return "".join(r.get("text", "") for r in o.get("runs", []))

def parse_row(r, src):
    if "playlistPanelVideoWrapperRenderer" in r:
        r = r["playlistPanelVideoWrapperRenderer"].get("primaryRenderer", {}).get("playlistPanelVideoRenderer", r)
    vid = r.get("videoId")
    title = runs_text(r.get("title"))
    runs = (r.get("longBylineText") or r.get("shortBylineText") or {}).get("runs", [])
    artists, album, year = [], "", 0
    for run in runs:
        be = (run.get("navigationEndpoint") or {}).get("browseEndpoint")
        t = run.get("text", "")
        if be:
            pt = be.get("browseEndpointContextSupportedConfigs", {}).get("browseEndpointContextMusicConfig", {}).get("pageType", "")
            if pt == "MUSIC_PAGE_TYPE_ARTIST": artists.append({"name": t, "ch": be.get("browseId", "")})
            elif pt == "MUSIC_PAGE_TYPE_ALBUM": album = t
        elif t.isdigit() and len(t) == 4: year = int(t)
        elif t.strip() in ("•", "&", ",") or not t.strip(): pass
    if not artists:  # artist runs without a link: take the first run
        if runs and runs[0].get("text", "").strip() not in ("", "•"):
            artists.append({"name": runs[0]["text"], "ch": ""})
    dur = 0
    lt = runs_text(r.get("lengthText"))
    if lt:
        try:
            parts = [int(x) for x in lt.split(":")]
            for p in parts: dur = dur * 60 + p
        except ValueError: pass
    mvt = (((r.get("navigationEndpoint") or {}).get("watchEndpoint") or {})
           .get("watchEndpointMusicSupportedConfigs", {}).get("watchEndpointMusicConfig", {}).get("musicVideoType", ""))
    explicit = any(((b.get("musicInlineBadgeRenderer") or {}).get("icon") or {}).get("iconType") == "MUSIC_EXPLICIT_BADGE"
                   for b in (r.get("badges") or []))
    if not vid or not title or not artists: return None
    return {"vid": vid, "title": title, "artists": artists, "album": album, "year": year, "dur": dur,
            "explicit": explicit, "mvt": mvt, "src": src}

def main(raw, out):
    rows = {}
    for fn in sorted(os.listdir(raw)):
        if not fn.endswith(".json") or fn.endswith(".req.json"): continue
        try: d = json.load(open(os.path.join(raw, fn), encoding="utf8"))
        except Exception: continue
        found = []
        walk(d, "playlistPanelVideoRenderer", found)
        for f in found:
            row = parse_row(f, fn)
            if not row or row["mvt"] != "MUSIC_VIDEO_TYPE_ATV": continue
            key = (row["artists"][0]["name"].lower().strip(), row["title"].lower().strip())
            if key in rows: continue
            sg = script_group(row["title"] + row["artists"][0]["name"])
            row["group"] = sg or file_category(fn) or "en"
            rows[key] = row
    with open(out, "w", encoding="utf8") as f:
        for row in rows.values(): f.write(json.dumps(row, ensure_ascii=False) + "\n")
    counts = {}
    for row in rows.values(): counts[row["group"]] = counts.get(row["group"], 0) + 1
    print(len(rows), "tracks", counts)

if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
