"""Czyszczenie Unicode: znaki niewidoczne, ukryty tekst w znakach tagów, homoglify."""

import re
import unicodedata
from dataclasses import dataclass

# Dodatkowe znaki wyglądające jak puste miejsce, a niebędące w kategorii Cf
_EXTRA_INVISIBLE = {0x3164, 0x115F, 0x1160, 0xFFA0, 0x2800, 0x180E, 0x034F}

# Tylko litery wyglądające jak łacińskie z cyrylicy i greki. NFKC załatwia już pełnoszerokie i matematyczne.
HOMOGLYPHS: dict[str, str] = {
    # cyrylica małe
    "а": "a", "е": "e", "о": "o", "р": "p", "с": "c", "у": "y", "х": "x", "і": "i", "ј": "j", "ѕ": "s",
    "ԁ": "d", "һ": "h", "ԛ": "q", "ԝ": "w", "ӏ": "l",
    # cyrylica wielkie
    "А": "A", "В": "B", "Е": "E", "К": "K", "М": "M", "Н": "H", "О": "O", "Р": "P", "С": "C", "Т": "T",
    "Х": "X", "У": "Y", "І": "I", "Ј": "J", "Ѕ": "S",
    # greka
    "ο": "o", "ν": "v", "ρ": "p", "ι": "i", "κ": "k", "υ": "u", "χ": "x", "γ": "y", "α": "a",
    "Α": "A", "Β": "B", "Ε": "E", "Ζ": "Z", "Η": "H", "Ι": "I", "Κ": "K", "Μ": "M", "Ν": "N", "Ο": "O",
    "Ρ": "P", "Τ": "T", "Υ": "Y", "Χ": "X",
    # litery łacińskie spoza ASCII wyglądające jak ASCII
    "ɡ": "g", "ı": "i", "ǀ": "l",
}

_LETTER_RUN = re.compile(r"[^\W\d_]+")


@dataclass
class InvisibleReport:
    total: int = 0
    inside_words: int = 0
    tag_chars: int = 0
    smuggled: str = ""  # tekst ukryty w znakach tagów (U+E0020..U+E007E)


def _is_invisible(ch: str) -> bool:
    if ch in "\t\n\r":
        return False
    cp = ord(ch)
    if cp in _EXTRA_INVISIBLE or 0xFE00 <= cp <= 0xFE0F or 0xE0100 <= cp <= 0xE01EF:
        return True
    return unicodedata.category(ch) in ("Cf", "Cc")


def strip_invisible(text: str) -> tuple[str, InvisibleReport]:
    rep = InvisibleReport()
    out: list[str] = []
    smuggled: list[str] = []
    i, n = 0, len(text)
    while i < n:
        ch = text[i]
        if not _is_invisible(ch):
            out.append(ch)
            i += 1
            continue
        j = i
        while j < n and _is_invisible(text[j]):
            j += 1
        for c in text[i:j]:
            cp = ord(c)
            rep.total += 1
            if 0xE0020 <= cp <= 0xE007E:
                rep.tag_chars += 1
                smuggled.append(chr(cp - 0xE0000))
            elif 0xE0000 <= cp <= 0xE007F:
                rep.tag_chars += 1
        before = text[i - 1] if i > 0 else ""
        after = text[j] if j < n else ""
        if before.isalnum() and after.isalnum():
            rep.inside_words += 1
        i = j
    rep.smuggled = "".join(smuggled)
    return "".join(out), rep


def fold_homoglyphs(text: str) -> tuple[str, int]:
    """Zamienia lookalike'i tylko w słowach mieszających łacinę z cyrylicą/grecką. Czysty rosyjski tekst zostaje."""
    changed_words = 0

    def fix(m: re.Match) -> str:
        nonlocal changed_words
        word = m.group(0)
        if not any(c in HOMOGLYPHS for c in word):
            return word
        has_latin = any(("LATIN" in unicodedata.name(c, "")) for c in word if c not in HOMOGLYPHS)
        if not has_latin:
            return word
        changed_words += 1
        return "".join(HOMOGLYPHS.get(c, c) for c in word)

    return _LETTER_RUN.sub(fix, text), changed_words
