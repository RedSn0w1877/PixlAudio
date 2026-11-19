#!/usr/bin/env python3
"""Cuts InnerTube parser fixtures from saved responses (docs/brain-spec.md, 10.4 "Parser fixtures").

  python tools/brain/cut_fixtures.py <folder with the raw captures>

Writes app/src/test/resources/innertube/<name>.json (the response with the keys no parser reads
removed) and <name>.expected.json (the rows this script reads out of it with its own, deliberately
plain, extraction). The Kotlin parser test and the Swift one both have to produce exactly the
expected file from the trimmed response, so a parser bug cannot hide behind its own output.

The raw captures were made anonymously (no account, no cookie) for famous public tracks.
"""
import json
import os
import re
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, "app", "src", "test", "resources", "innertube")

DROP = {
    "menu", "trackingParams", "clickTrackingParams", "queueNavigationEndpoint", "loggingContext",
    "accessibility", "accessibilityData", "responseContext", "playerOverlays", "videoReporting",
    "queueContextParams", "shuffleToggleButton", "thumbnailOverlay", "overlay", "subscriptionButton",
    "moreButton", "shareEndpoint", "description", "microformat", "playerParams", "playlistSetVideoId",
    "canReorder", "selected", "shortBylineText", "displayPriority", "flexColumnDisplayStyle",
    "itemHeight", "aspectRatio", "headerStyle", "shelfDivider", "bottomEndpoint", "bottomText",
    "moreContentButton", "numItemsPerColumn", "itemSize", "thumbnailCornerOverlay", "musicQueueConfig",
    "hack", "currentVideoEndpoint", "monthlyListenerCount", "playButton", "isInfinite", "numItemsToShow",
    "dedupeAgainstLocalQueue", "syncMode", "queueUpdateSection", "uniqueId", "isSelected", "playlistItemData",
}


def trim(node):
    if isinstance(node, dict):
        out = {}
        for key, value in node.items():
            if key in DROP:
                continue
            if key == "thumbnails" and isinstance(value, list) and value:
                out[key] = [value[-1]]
                continue
            out[key] = trim(value)
        return out
    if isinstance(node, list):
        return [trim(v) for v in node]
    return node


def find(node, key, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if k == key:
                out.append(v)
            find(v, key, out)
    elif isinstance(node, list):
        for v in node:
            find(v, key, out)
    return out


def first(node, key):
    found = find(node, key, [])
    return found[0] if found else None


def text_of(node):
    if not isinstance(node, dict):
        return ""
    return "".join(r.get("text", "") for r in node.get("runs", []))


def page_type(run):
    return (((run.get("navigationEndpoint") or {}).get("browseEndpoint") or {})
            .get("browseEndpointContextSupportedConfigs", {})
            .get("browseEndpointContextMusicConfig", {}).get("pageType"))


def browse_id(run):
    return ((run.get("navigationEndpoint") or {}).get("browseEndpoint") or {}).get("browseId")


def seconds(text):
    if not text or not re.fullmatch(r"\d+(:\d{1,2}){1,2}", text):
        return 0
    total = 0
    for part in text.split(":"):
        total = total * 60 + int(part)
    return total


def kind(music_video_type):
    return {
        "MUSIC_VIDEO_TYPE_ATV": "ATV", "MUSIC_VIDEO_TYPE_OMV": "OMV", "MUSIC_VIDEO_TYPE_UGC": "UGC",
        "MUSIC_VIDEO_TYPE_PODCAST_EPISODE": "PODCAST",
    }.get(music_video_type, "UNKNOWN")


def byline(runs):
    artists, album, album_id, year = [], None, None, 0
    plain_before_bullet = []
    seen_bullet = False
    for run in runs:
        t = run.get("text", "")
        pt = page_type(run)
        if pt == "MUSIC_PAGE_TYPE_ARTIST" or pt == "MUSIC_PAGE_TYPE_USER_CHANNEL":
            artists.append({"name": t, "id": browse_id(run)})
        elif pt == "MUSIC_PAGE_TYPE_ALBUM":
            album, album_id = t, browse_id(run)
        elif re.fullmatch(r"\d{4}", t.strip()):
            year = int(t.strip())
        elif t.strip() == "•":
            seen_bullet = True
        elif not seen_bullet and t.strip() not in ("&", ",", ""):
            plain_before_bullet.append(t.strip())
    if not artists:
        artists = [{"name": n, "id": None} for n in plain_before_bullet]
    return artists, album, album_id, year


def panel_row(r):
    artists, album, album_id, year = byline((r.get("longBylineText") or {}).get("runs", []))
    thumbs = ((r.get("thumbnail") or {}).get("thumbnails") or [])
    explicit = any(
        ((b.get("musicInlineBadgeRenderer") or {}).get("icon") or {}).get("iconType") == "MUSIC_EXPLICIT_BADGE"
        for b in r.get("badges", [])
    )
    mvt = first(r.get("navigationEndpoint") or {}, "musicVideoType")
    return {
        "videoId": r.get("videoId"), "title": text_of(r.get("title")), "artists": artists,
        "album": album, "albumId": album_id, "year": year,
        "durationSec": seconds(text_of(r.get("lengthText"))),
        "thumb": thumbs[-1]["url"] if thumbs else None, "explicit": explicit, "type": kind(mvt),
    }


def radio_expected(doc):
    panel = first(doc, "playlistPanelRenderer") or first(doc, "playlistPanelContinuation") or {}
    rows = []
    for item in panel.get("contents", []):
        r = item.get("playlistPanelVideoRenderer")
        if r is None:
            r = ((item.get("playlistPanelVideoWrapperRenderer") or {}).get("primaryRenderer") or {}) \
                .get("playlistPanelVideoRenderer")
        if r is None or "unplayableText" in r or not r.get("videoId"):
            continue
        rows.append(panel_row(r))
    token = None
    for c in panel.get("continuations", []):
        token = (c.get("nextRadioContinuationData") or {}).get("continuation") or token
    chips = []
    queue = first(doc, "musicQueueRenderer") or {}
    for chip in find(queue.get("subHeaderChipCloud") or {}, "chipCloudChipRenderer", []):
        we = first(chip.get("navigationEndpoint") or {}, "watchEndpoint") or {}
        if we.get("playlistId"):
            chips.append({"label": text_of(chip.get("text")), "playlistId": we.get("playlistId"), "params": we.get("params")})
    related = None
    for tab in find(doc, "tabRenderer", []):
        run = {"navigationEndpoint": tab.get("endpoint")}
        if page_type(run) == "MUSIC_PAGE_TYPE_TRACK_RELATED":
            related = browse_id(run)
    return {"rows": rows, "continuation": token, "chips": chips, "relatedBrowseId": related}


def list_item_row(r):
    columns = [c.get("musicResponsiveListItemFlexColumnRenderer", {}).get("text", {}) for c in r.get("flexColumns", [])]
    title_runs = columns[0].get("runs", []) if columns else []
    we = first(title_runs, "watchEndpoint") or {}
    runs = []
    for c in columns[1:]:
        if runs:
            runs.append({"text": " • "})
        runs.extend(c.get("runs", []))
    artists, album, album_id, year = byline(runs)
    plays = None
    for run in runs:
        m = re.fullmatch(r"([\d.]+)([KMB]?) (plays|views)", run.get("text", "").strip())
        if m:
            plays = int(round(float(m.group(1)) * {"": 1, "K": 1e3, "M": 1e6, "B": 1e9}[m.group(2)]))
    artists = [a for a in artists if not re.fullmatch(r"[\d.]+[KMB]? (plays|views)", a["name"])]
    thumbs = find(r.get("thumbnail") or {}, "thumbnails", [])
    fixed = text_of(first(r.get("fixedColumns") or {}, "text") or {})
    explicit = any(
        ((b.get("musicInlineBadgeRenderer") or {}).get("icon") or {}).get("iconType") == "MUSIC_EXPLICIT_BADGE"
        for b in r.get("badges", [])
    )
    return {
        "videoId": we.get("videoId"), "title": "".join(x.get("text", "") for x in title_runs), "artists": artists,
        "album": album, "albumId": album_id, "year": year, "durationSec": seconds(fixed),
        "thumb": thumbs[0][-1]["url"] if thumbs and thumbs[0] else None, "explicit": explicit,
        "type": kind(first(we, "musicVideoType")), "plays": plays,
    }


def shelves(doc):
    out = []
    for section in (first(doc, "sectionListRenderer") or {}).get("contents", []):
        for name, body in section.items():
            if name not in ("musicCarouselShelfRenderer", "musicShelfRenderer"):
                continue
            header = text_of(first(body.get("header") or {}, "title") or body.get("title") or {})
            if not header:
                continue
            out.append((header, body.get("contents", [])))
    return out


def two_row_artist(item):
    r = item.get("musicTwoRowItemRenderer")
    if not r:
        return None
    run = {"navigationEndpoint": r.get("navigationEndpoint")}
    if page_type(run) != "MUSIC_PAGE_TYPE_ARTIST":
        return None
    return {"name": text_of(r.get("title")), "id": browse_id(run)}


def two_row_release(item):
    r = item.get("musicTwoRowItemRenderer")
    if not r:
        return None
    run = {"navigationEndpoint": r.get("navigationEndpoint")}
    if page_type(run) != "MUSIC_PAGE_TYPE_ALBUM":
        return None
    year = 0
    for sub in (r.get("subtitle") or {}).get("runs", []):
        if re.fullmatch(r"\d{4}", sub.get("text", "").strip()):
            year = int(sub["text"].strip())
    return {"browseId": browse_id(run), "title": text_of(r.get("title")), "year": year}


def related_expected(doc):
    also, other, similar = [], [], []
    for header, items in shelves(doc):
        songs = [list_item_row(i["musicResponsiveListItemRenderer"]) for i in items if "musicResponsiveListItemRenderer" in i]
        songs = [s for s in songs if s["videoId"]]
        artists = [a for a in (two_row_artist(i) for i in items) if a]
        if songs:
            if "performances" in header.lower():
                other.extend(songs)
            elif not also:
                also = songs
        elif artists and len(artists) == len(items) and not similar:
            similar = artists
    return {"youMightAlsoLike": also, "otherPerformances": other, "similarArtists": similar}


def artist_expected(doc):
    header = first(doc.get("header") or {}, "musicImmersiveHeaderRenderer") or first(doc.get("header") or {}, "musicVisualHeaderRenderer") or {}
    we = first(header.get("startRadioButton") or {}, "watchEndpoint") or {}
    top, albums, singles, fans = [], [], [], []
    for title, items in shelves(doc):
        songs = [list_item_row(i["musicResponsiveListItemRenderer"]) for i in items if "musicResponsiveListItemRenderer" in i]
        songs = [s for s in songs if s["videoId"]]
        releases = [x for x in (two_row_release(i) for i in items) if x]
        artists = [a for a in (two_row_artist(i) for i in items) if a]
        if songs and not top:
            top = songs
        elif releases and len(releases) == len(items):
            (singles if "single" in title.lower() else albums).extend(releases)
        elif artists and len(artists) == len(items) and not fans:
            fans = artists
    return {
        "name": text_of(header.get("title")),
        "radio": {"videoId": we.get("videoId"), "playlistId": we.get("playlistId"), "params": we.get("params")} if we.get("playlistId") else None,
        "topSongs": top, "albums": albums, "singles": singles, "fansAlsoLike": fans,
    }


def release_item(item):
    """One musicTwoRowItemRenderer of a discography grid or an artist carousel, read plainly."""
    r = item.get("musicTwoRowItemRenderer")
    if not r:
        return None
    run = {"navigationEndpoint": r.get("navigationEndpoint")}
    if page_type(run) != "MUSIC_PAGE_TYPE_ALBUM":
        return None
    year, kind_name = 0, "UNKNOWN"
    for sub in (r.get("subtitle") or {}).get("runs", []):
        t = sub.get("text", "").strip()
        if re.fullmatch(r"\d{4}", t):
            year = int(t)
        elif t.lower() in ("album", "single", "ep"):
            kind_name = t.upper()
    audio = None
    for ep in find(r.get("menu") or {}, "watchPlaylistEndpoint", []):
        pid = ep.get("playlistId", "")
        if pid.startswith("OLAK5uy_") and audio is None:
            audio = pid
    thumbs = find(r.get("thumbnailRenderer") or {}, "thumbnails", [])
    return {
        "browseId": browse_id(run), "title": text_of(r.get("title")), "year": year, "kind": kind_name,
        "audioPlaylistId": audio, "thumb": thumbs[0][-1]["url"] if thumbs and thumbs[0] else None,
    }


def discography_expected(doc):
    grid = first(doc, "gridRenderer") or {}
    return {"releases": [x for x in (release_item(i) for i in grid.get("items", [])) if x]}


def album_audio_expected(doc):
    canon = ((first(doc, "microformatDataRenderer") or {}).get("urlCanonical")) or ""
    m = re.search(r"[?&]list=(OLAK5uy_[A-Za-z0-9_-]+)", canon)
    return {"audioPlaylistId": m.group(1) if m else None}


def playlist_expected(doc):
    shelf = first(doc, "musicPlaylistShelfRenderer")
    appended = first(doc, "appendContinuationItemsAction")
    items = shelf.get("contents", []) if shelf else (appended.get("continuationItems", []) if appended else [])
    rows, token = [], None
    for item in items:
        if "musicResponsiveListItemRenderer" in item:
            row = list_item_row(item["musicResponsiveListItemRenderer"])
            if row["videoId"]:
                rows.append(row)
        elif "continuationItemRenderer" in item:
            cmd = first(item["continuationItemRenderer"], "continuationCommand") or {}
            token = cmd.get("token") or token
    return {"rows": rows, "continuation": token}


def get_queue_expected(doc):
    rows = []
    for data in doc.get("queueDatas", []):
        r = (data.get("content") or {}).get("playlistPanelVideoRenderer")
        if r and r.get("videoId") and "unplayableText" not in r:
            rows.append(panel_row(r))
    return {"rows": rows}


def search_expected(doc):
    rows = []
    for r in find(doc, "musicResponsiveListItemRenderer", []):
        row = list_item_row(r)
        if not row["videoId"]:
            continue
        if row["durationSec"] == 0:
            for c in r.get("flexColumns", [])[1:]:
                for run in c.get("musicResponsiveListItemFlexColumnRenderer", {}).get("text", {}).get("runs", []):
                    clock = seconds(run.get("text", "").strip())
                    if clock and row["durationSec"] == 0:
                        row["durationSec"] = clock
        rows.append(row)
    return {"rows": rows}


def trim_keep_menu(node):
    """Like trim(), but keeps the first menu item (Shuffle play: the audio playlist id) of a release card."""
    if isinstance(node, dict):
        out = {}
        for key, value in node.items():
            if key == "menu":
                items = (value.get("menuRenderer") or {}).get("items", [])
                keep = [i for i in items if "watchPlaylistEndpoint" in json.dumps(i)][:1]
                out[key] = {"menuRenderer": {"items": [trim(i) if False else strip_clicks(i) for i in keep]}}
                continue
            if key in DROP:
                continue
            if key == "thumbnails" and isinstance(value, list) and value:
                out[key] = [value[-1]]
                continue
            out[key] = trim_keep_menu(value)
        return out
    if isinstance(node, list):
        return [trim_keep_menu(v) for v in node]
    return node


def strip_clicks(node):
    if isinstance(node, dict):
        return {k: strip_clicks(v) for k, v in node.items() if k not in ("clickTrackingParams", "trackingParams", "accessibility", "accessibilityData")}
    if isinstance(node, list):
        return [strip_clicks(v) for v in node]
    return node


FIXTURES = [
    # (output name, raw file, kind, keep at most N panel rows or None)
    ("radio_song", "next_B_min.json", "radio", None),
    ("radio_song_cont1", "next_cont_body.json", "radio", None),
    ("radio_vpop", "radio_vpop.json", "radio", 12),
    ("radio_artist", "next_artist_radio_RDEM.json", "radio", None),
    ("radio_short", "radio_pair_nirvana_b.json", "radio", None),
    ("radio_music_videos", "next_edge_dQw4w9WgXcQ.json", "radio", 12),
    ("radio_invalid_id", "next_edge_AAAAAAAAAAA.json", "radio", None),
    ("related", "related_MPTRt_4U7yfKKFZL_vd.json", "related", None),
    ("artist_page", "artist_UClYV6hHlupm_S_ObS1W-DYw_vd.json", "artist", None),
]

# Stage 2a. (output name, raw file, kind, keep at most N items of the main list or None)
FIXTURES_2A = [
    ("discography", "discog_noparams.json", "discography", 14),
    ("album_audio", "album_afterhours.json", "album_audio", None),
    ("playlist_album", "album_as_playlist.json", "playlist", None),
    ("playlist_top_songs", "pl_topsongs.json", "playlist", 12),
    ("playlist_cont", "pl_topsongs_cont1.json", "playlist", 12),
    ("get_queue", "get_queue_5.json", "get_queue", None),
    ("search_songs", "search_Blinding_Lights_The_Weeknd.json", "search", 8),
]


def cut_2a(raw):
    for name, source, what, keep in FIXTURES_2A:
        with open(os.path.join(raw, source), encoding="utf-8") as f:
            doc = json.load(f)
        if what == "discography":
            doc = trim_keep_menu(doc)
            grid = first(doc, "gridRenderer")
            grid["items"] = grid["items"][:keep]
            expected = discography_expected(doc)
        elif what == "album_audio":
            micro = first(doc, "microformatDataRenderer")
            doc = {"microformat": {"microformatDataRenderer": {"urlCanonical": micro["urlCanonical"], "title": micro.get("title")}}}
            expected = album_audio_expected(doc)
        elif what == "playlist":
            doc = trim(doc)
            shelf = first(doc, "musicPlaylistShelfRenderer")
            appended = first(doc, "appendContinuationItemsAction")
            if keep is not None:
                if shelf is not None:
                    tail = [i for i in shelf["contents"] if "continuationItemRenderer" in i]
                    shelf["contents"] = [i for i in shelf["contents"] if "continuationItemRenderer" not in i][:keep] + tail
                elif appended is not None:
                    tail = [i for i in appended["continuationItems"] if "continuationItemRenderer" in i]
                    appended["continuationItems"] = [i for i in appended["continuationItems"] if "continuationItemRenderer" not in i][:keep] + tail
            expected = playlist_expected(doc)
        elif what == "get_queue":
            doc = trim(doc)
            expected = get_queue_expected(doc)
        else:
            doc = trim(doc)
            shelf = first(doc, "musicShelfRenderer")
            if keep is not None and shelf is not None:
                shelf["contents"] = shelf["contents"][:keep]
            expected = search_expected(doc)
        with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
        with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(expected, f, ensure_ascii=False, indent=1)
        size = os.path.getsize(os.path.join(OUT, name + ".json"))
        print("%-22s %7d bytes" % (name, size))


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if not args:
        raise SystemExit(__doc__)
    raw = args[0]
    os.makedirs(OUT, exist_ok=True)
    if "--2a" in sys.argv:
        # Only the stage 2a fixtures: the earlier ones are not rewritten.
        cut_2a(raw)
        return
    for name, source, what, keep in FIXTURES:
        with open(os.path.join(raw, source), encoding="utf-8") as f:
            doc = trim(json.load(f))
        if keep is not None:
            panel = first(doc, "playlistPanelRenderer") or first(doc, "playlistPanelContinuation")
            if panel and "contents" in panel:
                panel["contents"] = panel["contents"][:keep]
        expected = {"radio": radio_expected, "related": related_expected, "artist": artist_expected}[what](doc)
        with open(os.path.join(OUT, name + ".json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
        with open(os.path.join(OUT, name + ".expected.json"), "w", encoding="utf-8", newline="\n") as f:
            json.dump(expected, f, ensure_ascii=False, indent=1)
        size = os.path.getsize(os.path.join(OUT, name + ".json"))
        rows = len(expected.get("rows", expected.get("youMightAlsoLike", expected.get("topSongs", []))))
        print("%-22s %7d bytes  %d rows" % (name, size, rows))


if __name__ == "__main__":
    main()
