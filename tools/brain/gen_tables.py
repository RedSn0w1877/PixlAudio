#!/usr/bin/env python3
"""Shipped tables of the music brain (docs/brain-spec.md, 10.3 rule 15 and 10.5).

Two steps, both deterministic:

  python tools/brain/gen_tables.py seed      # (re)writes brain/tables/fold-v1.tsv from Unicode data
  python tools/brain/gen_tables.py kotlin    # writes FoldTable.kt and TitleRules.kt from the two .tsv files

The .tsv files are the reviewed sources: edit them by hand, then run `kotlin`. `seed` exists so the
first version of fold-v1.tsv is reproducible; it is not part of the build. `TablesInSyncTest` in
:brain fails when the generated Kotlin and the .tsv files disagree. The iOS port generates its
Swift tables from the same two files.

fold-v1.tsv:        <code point, hex> TAB <replacement, space-separated hex code points; empty = delete>
title-rules-v1.tsv: <kind> TAB <token sequence>
"""
import os
import sys
import unicodedata

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
TABLES = os.path.join(ROOT, "brain", "tables")
OUT = os.path.join(ROOT, "brain", "src", "main", "kotlin", "com", "theveloper", "pixelplay", "brain")

# Letters Unicode does not decompose.
SPECIAL = {
    0x00D0: "d", 0x00F0: "d", 0x0110: "d", 0x0111: "d",
    0x00DE: "th", 0x00FE: "th",
    0x00C6: "ae", 0x00E6: "ae", 0x01E2: "ae", 0x01E3: "ae", 0x01FC: "ae", 0x01FD: "ae",
    0x0152: "oe", 0x0153: "oe",
    0x00D8: "o", 0x00F8: "o", 0x01FE: "o", 0x01FF: "o",
    0x0141: "l", 0x0142: "l",
    0x0126: "h", 0x0127: "h",
    0x0131: "i", 0x0138: "k",
    0x014A: "n", 0x014B: "n",
    0x0166: "t", 0x0167: "t",
    0x017F: "s", 0x00DF: "ss", 0x1E9E: "ss",
    0x00AA: "a", 0x00BA: "o",
    0x00B2: "2", 0x00B3: "3", 0x00B9: "1",
}


def ascii_letters(text):
    return text != "" and all("a" <= c <= "z" for c in text)


def latin_base(cp):
    """The ASCII letters a precomposed Latin letter folds to, or None."""
    if cp in SPECIAL:
        return SPECIAL[cp]
    ch = chr(cp)
    if not unicodedata.category(ch).startswith("L"):
        return None
    decomposed = unicodedata.normalize("NFKD", ch)
    base = "".join(c for c in decomposed if not unicodedata.combining(c)).lower()
    base = base.replace("ʼ", "")  # the apostrophe of U+0149
    if ascii_letters(base):
        return base
    name = unicodedata.name(ch, "")
    for prefix in ("LATIN CAPITAL LETTER ", "LATIN SMALL LETTER "):
        if name.startswith(prefix):
            word = name[len(prefix):].split(" ")[0]
            if len(word) == 1 and "A" <= word <= "Z":
                return word.lower()
    lower = ch.lower()
    if lower != ch and len(lower) == 1:
        return lower  # no ASCII base: at least fold the case
    return None


def seed():
    rows = {}
    # C1 controls and Latin-1 punctuation: a space (NBSP included); the letter-like ones are in SPECIAL.
    for cp in range(0x0080, 0x00C0):
        rows[cp] = SPECIAL.get(cp, " ")
    rows[0x00B5] = "μ"  # micro sign to Greek small mu
    rows[0x00D7] = " "
    rows[0x00F7] = " "
    for lo, hi in ((0x00C0, 0x024F), (0x1E00, 0x1EFF)):
        for cp in range(lo, hi + 1):
            if cp in rows:
                continue
            base = latin_base(cp)
            if base is not None and base != chr(cp):
                rows[cp] = base
    # Combining marks: deleted.
    for cp in range(0x0300, 0x0370):
        rows[cp] = ""
    # Greek and Cyrillic capitals to small letters.
    for lo, hi in ((0x0370, 0x03FF), (0x0400, 0x052F)):
        for cp in range(lo, hi + 1):
            ch = chr(cp)
            lower = ch.lower()
            if lower != ch and len(lower) == 1:
                rows[cp] = lower
    # General punctuation and CJK punctuation: a space.
    for lo, hi in ((0x2000, 0x206F), (0x3000, 0x303F)):
        for cp in range(lo, hi + 1):
            rows[cp] = " "
    # Full-width ASCII: its ASCII counterpart, then the ASCII rule.
    for cp in range(0xFF01, 0xFF5F):
        a = chr(cp - 0xFEE0)
        if "A" <= a <= "Z":
            rows[cp] = a.lower()
        elif ("a" <= a <= "z") or ("0" <= a <= "9"):
            rows[cp] = a
        else:
            rows[cp] = " "
    rows[0xFFFD] = " "

    os.makedirs(TABLES, exist_ok=True)
    with open(os.path.join(TABLES, "fold-v1.tsv"), "w", encoding="ascii", newline="\n") as f:
        f.write("# fold-v1: code point (hex) TAB replacement (space-separated hex code points; empty = delete).\n")
        f.write("# ASCII is handled in code: A-Z to a-z, letters and digits unchanged, the rest a space.\n")
        f.write("# Lone surrogates fold to a space in code. Anything not listed is unchanged.\n")
        for cp in sorted(rows):
            f.write("%04X\t%s\n" % (cp, " ".join("%04X" % ord(c) for c in rows[cp])))
    print("fold-v1.tsv: %d rows" % len(rows))


def read_fold():
    rows = []
    with open(os.path.join(TABLES, "fold-v1.tsv"), encoding="ascii") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            key, _, value = line.partition("\t")
            rows.append((int(key, 16), "".join(chr(int(h, 16)) for h in value.split())))
    rows.sort()
    for cp, _ in rows:
        if cp > 0xFFFF:
            raise SystemExit("fold-v1 keys must be in the BMP: %X" % cp)
    return rows


def read_rules():
    rules = []
    with open(os.path.join(TABLES, "title-rules-v1.tsv"), encoding="ascii") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            kind, _, tokens = line.partition("\t")
            rules.append((kind.strip(), tokens.strip()))
    return rules


def kt_escape(text):
    out = []
    for c in text:
        o = ord(c)
        if c == "\\":
            out.append("\\\\")
        elif c == '"':
            out.append('\\"')
        elif c == "$":
            out.append("\\$")
        elif 0x20 <= o < 0x7F:
            out.append(c)
        elif o <= 0xFFFF:
            out.append("\\u%04x" % o)
        else:
            o -= 0x10000
            out.append("\\u%04x\\u%04x" % (0xD800 + (o >> 10), 0xDC00 + (o & 0x3FF)))
    return "".join(out)


def chunks(text, size):
    return [text[i:i + size] for i in range(0, len(text), size)]


def kotlin():
    rows = read_fold()
    keys = "".join(chr(cp) for cp, _ in rows)
    # U+0001 separates replacements: it cannot occur in one.
    values = "\u0001".join(v for _, v in rows)
    lines = [
        "// GENERATED by tools/brain/gen_tables.py from brain/tables/fold-v1.tsv. Do not edit.",
        "package com.theveloper.pixelplay.brain",
        "",
        "/** fold-v1 (docs/brain-spec.md, 10.5): the non-ASCII rows of [BrainText.fold], sorted by key. */",
        "internal object FoldTable {",
        "    const val VERSION = 1",
        "    const val ROWS = %d" % len(rows),
        "",
        "    /** One UTF-16 unit per row, ascending. */",
        "    val KEYS: String =",
    ]
    key_chunks = chunks(keys, 16)
    for i, chunk in enumerate(key_chunks):
        lines.append('        "%s"%s' % (kt_escape(chunk), " +" if i < len(key_chunks) - 1 else ""))
    lines.append("")
    lines.append("    /** The replacements in key order, separated by U+0001. An empty entry deletes the scalar. */")
    lines.append("    val VALUES: String =")
    value_chunks = chunks(values, 48)
    for i, chunk in enumerate(value_chunks):
        lines.append('        "%s"%s' % (kt_escape(chunk), " +" if i < len(value_chunks) - 1 else ""))
    lines.append("}")
    lines.append("")
    with open(os.path.join(OUT, "FoldTable.kt"), "w", encoding="ascii", newline="\n") as f:
        f.write("\n".join(lines))

    rules = read_rules()
    flag_names = ["live", "remix", "acoustic", "instrumental", "remaster", "cover", "karaoke", "sped-up", "slowed", "re-recorded"]
    lines = [
        "// GENERATED by tools/brain/gen_tables.py from brain/tables/title-rules-v1.tsv. Do not edit.",
        "package com.theveloper.pixelplay.brain",
        "",
        "/** title-rules-v1 (docs/brain-spec.md, 10.5), in file order. Token sequences are already folded. */",
        "internal object TitleRules {",
        "    const val VERSION = 1",
        "",
        "    /** Flag rules: the bit index into versionFlags, then the token sequence a segment must contain. */",
        "    val FLAG_BITS: IntArray = intArrayOf(%s)" % ", ".join(str(flag_names.index(k)) for k, _ in rules if k in flag_names),
        "    val FLAG_TOKENS: Array<String> = arrayOf(",
    ]
    for kind, tokens in rules:
        if kind in flag_names:
            lines.append('        "%s",' % kt_escape(tokens))
    lines.append("    )")
    lines.append("")
    lines.append("    /** Neutral rules: a segment that starts with one of these token sequences is dropped without a flag. */")
    lines.append("    val NEUTRAL_PREFIX: Array<String> = arrayOf(")
    for kind, tokens in rules:
        if kind == "neutral-prefix":
            lines.append('        "%s",' % kt_escape(tokens))
    lines.append("    )")
    lines.append("")
    lines.append("    /** Neutral rules: a segment equal to one of these token sequences is dropped without a flag. */")
    lines.append("    val NEUTRAL_EQUALS: Array<String> = arrayOf(")
    for kind, tokens in rules:
        if kind == "neutral-equals":
            lines.append('        "%s",' % kt_escape(tokens))
    lines.append("    )")
    lines.append("}")
    lines.append("")
    unknown = [k for k, _ in rules if k not in flag_names and k not in ("neutral-prefix", "neutral-equals")]
    if unknown:
        raise SystemExit("unknown rule kinds: %s" % unknown)
    with open(os.path.join(OUT, "TitleRules.kt"), "w", encoding="ascii", newline="\n") as f:
        f.write("\n".join(lines))
    print("FoldTable.kt: %d rows; TitleRules.kt: %d rules" % (len(rows), len(rules)))


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "seed":
        seed()
    elif mode == "kotlin":
        kotlin()
    else:
        raise SystemExit(__doc__)
