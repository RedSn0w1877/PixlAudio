#!/usr/bin/env python3
"""Cuts the text corpus of the brain's golden vectors (docs/brain-spec.md, 10.4: fold.jsonl, title.jsonl).

  python tools/brain/cut_corpus.py <folder with the raw captures>

Writes brain/src/test/resources/brain-corpus/names.txt (1,000 artist names and other strings) and
titles.txt (300 track titles, at least 60 of them Vietnamese, Korean or Japanese), one per line,
UTF-8, LF. The golden generator reads them; nothing else does. The captures were made anonymously
for famous public tracks; the lines below them are famous public artist names and titles typed in by
hand so the three scripts the spec names are all covered.
"""
import json
import os
import sys

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
OUT = os.path.join(ROOT, "brain", "src", "test", "resources", "brain-corpus")

EXTRA_NAMES = [
    "米津玄師", "宇多田ヒカル", "あいみょん", "Official髭男dism", "King Gnu", "YOASOBI", "back number",
    "Mrs. GREEN APPLE", "椎名林檎", "サザンオールスターズ", "スピッツ", "ＡＢＣ　ＤＥＦ", "ｱｲﾐｮﾝ",
    "방탄소년단", "블랙핑크", "아이유", "뉴진스", "세븐틴", "르세라핌", "에스파", "볼빨간사춘기", "악동뮤지션",
    "Sơn Tùng M-TP", "Hoàng Thùy Linh", "Đen Vâu", "Mỹ Tâm", "Bích Phương", "HIEUTHUHAI", "Vũ.", "Chillies",
    "Ngọt", "Thịnh Suy", "Hà Anh Tuấn", "Trúc Nhân", "Đức Phúc", "Erik", "MIN", "tlinh", "Wren Evans",
    "Phương Mỹ Chi", "Văn Mai Hương", "Tóc Tiên", "Noo Phước Thịnh", "Nguyễn Trần Trung Quân",
    "Beyoncé", "Sigur Rós", "Björk", "Mötley Crüe", "Motörhead", "Zoë Keating", "Łąki Łan", "Françoise Hardy",
    "Céline Dion", "Måneskin", "Røyksopp", "Kraftklub", "Die Ärzte", "Straße", "Ænima", "Æther Realm",
    "Tyler, The Creator", "AC/DC", "P!nk", "$uicideboy$", "blink-182", "*NSYNC", "M.I.A.", "will.i.am",
    "Кино", "Молчат Дома", "ДДТ", "Земфира", "Μίκης Θεοδωράκης", "Άννα Βίσση",
    "Fleetwood Mac", "The Weeknd", "Kendrick Lamar", "NewJeans", "Nirvana", "Burial", "d4vd", "Chappell Roan",
    "Sabrina Carpenter", "Taylor Swift", "Billie Eilish", "Daft Punk", "Radiohead", "Shakira", "Burna Boy",
    "  leading and trailing  ", "MiXeD CaSe NaMe", "tab\tseparated", "ｆｕｌｌｗｉｄｔｈ", "“quoted”", "dash – en — em",
    "Nguyễn", "Nguyễn", "Trần Thị", "é", "ố", " nbsp ", "ﬁ ligature", "ß", "İstanbul", "ıi",
]

EXTRA_TITLES = [
    "夜に駆ける", "白日", "マリーゴールド", "Pretender", "紅蓮華", "Lemon", "アイドル", "群青", "怪物 (Anime Version)",
    "First Love (2022 Remastered)", "丸の内サディスティック (Live)", "残酷な天使のテーゼ (Instrumental)",
    "ドライフラワー [Acoustic Ver.]", "廻廻奇譚 - Remix", "炎 （カラオケ）", "夜に駆ける（Instrumental）",
    "봄날", "Dynamite (Acoustic Remix)", "밤편지", "Ditto", "Super Shy", "뚜두뚜두 (DDU-DU DDU-DU)", "좋은 날",
    "에잇 (Prod.&Feat. SUGA of BTS)", "사건의 지평선", "Love Dive (Live)", "Hype Boy - Sped Up", "Blue Hour (Japanese Ver.)",
    "Chúng Ta Của Hiện Tại", "Hãy Trao Cho Anh (feat. Snoop Dogg)", "Nơi Này Có Anh", "Lạc Trôi (Triple D Remix)",
    "See Tình", "See Tình (Speed Up Version)", "Để Mị Nói Cho Mà Nghe", "Bắc Kim Thang", "Em Của Ngày Hôm Qua",
    "Có Chắc Yêu Là Đây - Live at Sky Tour", "Mang Tiền Về Cho Mẹ (feat. Nguyên Thảo)", "Đi Về Nhà", "Nàng Thơ",
    "Thích Em Hơi Nhiều (Acoustic)", "Waiting For You (Karaoke)", "Ngày Mai Người Ta Lấy Chồng - Lofi Ver.",
    "Bông Hoa Đẹp Nhất (Cover)", "Vì Anh Đâu Có Biết (Slowed + Reverb)", "Tháng Tư Là Lời Nói Dối Của Em",
    "Anh Đã Quen Với Cô Đơn", "Đừng Làm Trái Tim Anh Đau", "Phía Sau Một Cô Gái (Beat)", "Hơn Cả Yêu (Piano Version)",
    "Dreams (2004 Remaster)", "Love Story (Taylor's Version)", "All Too Well (10 Minute Version) (Taylor's Version) (From The Vault)",
    "Smells Like Teen Spirit - Remastered 2021", "Bohemian Rhapsody (Live Aid)", "Hotel California - Live On MTV, 1994",
    "Blinding Lights (with ROSALÍA) - Remix", "HUMBLE.", "Not Like Us", "DNA.", "m.A.A.d city (feat. MC Eiht)",
    "Archangel", "Espresso", "Espresso - Double Shot Version", "Here With Me", "Romantic Homicide", "Good Luck, Babe!",
    "bad guy (with Justin Bieber)", "Get Lucky (Radio Edit) [feat. Pharrell Williams & Nile Rodgers]",
    "Creep (Acoustic)", "Karma Police - Remastered", "Hips Don't Lie (feat. Wyclef Jean)", "Last Last",
    "Song 2", "Part 2 (Interlude)", "Intro", "(Intro)", "[Untitled]", "Untitled (How Does It Feel)", "7 rings",
    "Stay (Instrumental)", "Stay - Karaoke Version", "Stay (Originally Performed by The Kid LAROI) [Backing Track]",
    "Heat Waves (Slowed)", "Heat Waves - Sped Up", "Somebody That I Used To Know (Tribute Version)", "Africa (Cover)",
    "Hurt - Live", "Unplugged in New York: About a Girl", "Wonderwall (MTV Unplugged)", "One (Mono)", "One - Stereo Mix",
    "Levitating (feat. DaBaby)", "Levitating (The Blessed Madonna Remix) [feat. Madonna and Missy Elliott]",
    "Mr. Brightside (Jacques Lu Cont's Thin White Duke Mix)", "Around the World (Original Mix)", "One More Time - Club Mix",
    "Sweet Child O' Mine (Re-Recorded)", "Rerecorded Hits: Jump", "Fearless (John's Version)", "Live Forever",
    "Alive", "Concert for One", "The Remix Song", "Cover Me Up", "Nightcore - Angel With A Shotgun", "Piano Man",
    "With Or Without You", "From The Start", "Feat", "Explicit", "Clean", "Deluxe", "Song (Deluxe Edition)",
    "Song (Bonus Track)", "Song (Single Version)", "Song (Album Version)", "Song (Explicit)", "Song (Clean)",
    "Song - Radio Edit", "Song – Live", "Song — Acoustic", "Song (prod. Metro Boomin)", "Song (ft. Someone)",
    "Song (featuring Someone Else)", "Song (From \"A Film\")", "Song (Bootleg)", "Song (Mashup)", "Song (Rework)",
    "Song (VIP Flip)", "Song (RMX)", "Song (Inst.)", "Song (Stripped)", "Song (Speed Up)", "", "   ", "()", "- Live",
]


def walk(node, names, titles):
    if isinstance(node, dict):
        r = node.get("playlistPanelVideoRenderer")
        if isinstance(r, dict):
            runs = (r.get("title") or {}).get("runs") or []
            if runs and isinstance(runs[0].get("text"), str):
                titles.add(runs[0]["text"])
            for run in (r.get("longBylineText") or {}).get("runs") or []:
                text = run.get("text")
                if isinstance(text, str) and "navigationEndpoint" in run:
                    names.add(text)
        r = node.get("musicResponsiveListItemRenderer")
        if isinstance(r, dict):
            cols = r.get("flexColumns") or []
            for i, col in enumerate(cols[:3]):
                runs = ((col.get("musicResponsiveListItemFlexColumnRenderer") or {}).get("text") or {}).get("runs") or []
                for run in runs:
                    text = run.get("text")
                    if not isinstance(text, str) or len(text) > 120:
                        continue
                    if i == 0:
                        titles.add(text)
                    elif "navigationEndpoint" in run:
                        names.add(text)
        r = node.get("musicTwoRowItemRenderer")
        if isinstance(r, dict):
            runs = (r.get("title") or {}).get("runs") or []
            if runs and isinstance(runs[0].get("text"), str) and len(runs[0]["text"]) <= 120:
                names.add(runs[0]["text"])
        for v in node.values():
            walk(v, names, titles)
    elif isinstance(node, list):
        for v in node:
            walk(v, names, titles)


def is_vkj(text):
    for ch in text:
        c = ord(ch)
        if 0x3040 <= c <= 0x30FF or 0x4E00 <= c <= 0x9FFF or 0xAC00 <= c <= 0xD7A3 or 0x1E00 <= c <= 0x1EFF:
            return True
        if ch in "ăâđêôơưĂÂĐÊÔƠƯ":
            return True
    return False


def clean(text):
    return " ".join(text.replace("\r", " ").replace("\n", " ").split(" ")) if "\n" in text or "\r" in text else text


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    names, titles = set(), set()
    for name in sorted(os.listdir(sys.argv[1])):
        if not name.endswith(".json") or name.endswith(".req.json"):
            continue
        try:
            with open(os.path.join(sys.argv[1], name), encoding="utf-8") as f:
                walk(json.load(f), names, titles)
        except (ValueError, OSError):
            continue
    names = sorted(clean(n) for n in names if n.strip())
    titles = sorted(clean(t) for t in titles if t.strip())

    out_names = list(EXTRA_NAMES)
    seen = set(out_names)
    for n in names + titles:
        if len(out_names) >= 1000:
            break
        if n not in seen:
            seen.add(n)
            out_names.append(n)

    out_titles = list(EXTRA_TITLES)
    seen = set(out_titles)
    vkj = [t for t in titles if is_vkj(t) and t not in seen]
    rest = [t for t in titles if not is_vkj(t) and t not in seen]
    # Titles with brackets or a dash exercise the rules; take those first.
    rest.sort(key=lambda t: (0 if any(ch in t for ch in "([-–—") else 1, t))
    for t in vkj[:70] + rest:
        if len(out_titles) >= 300:
            break
        if t not in seen:
            seen.add(t)
            out_titles.append(t)

    os.makedirs(OUT, exist_ok=True)
    for file_name, lines in (("names.txt", out_names), ("titles.txt", out_titles)):
        with open(os.path.join(OUT, file_name), "w", encoding="utf-8", newline="\n") as f:
            for line in lines:
                f.write(line.replace("\n", " ") + "\n")
        print(file_name, len(lines))
    print("vkj titles:", sum(1 for t in out_titles if is_vkj(t)))


if __name__ == "__main__":
    main()
