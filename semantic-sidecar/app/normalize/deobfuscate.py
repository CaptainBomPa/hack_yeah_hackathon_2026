"""Rozwijanie zapisów utrudniających czytanie: leetspeak, litery rozstrzelone, naprzemienna wielkość liter, ukryty tekst w HTML/markdown."""

import re

from app.normalize.wordlist import COMMON

_LEET = {"0": "o", "3": "e", "4": "a", "5": "s", "7": "t", "@": "a", "$": "s"}
_LEET_CHARS = set("013457@$")
_TOKEN = re.compile(r"[A-Za-z0-9@$]+")


_INTERIOR = re.compile(r"[A-Za-z][0-9@$]+[A-Za-z]")


def deleet(text: str) -> tuple[str, int]:
    """Rozwija leetspeak. Zwraca (tekst, liczba tokenów, które po rozwinięciu są słowami ze słownika).

    Token zmieniamy tylko gdy wynik jest słowem ze słownika albo gdy znak leet stoi wewnątrz słowa i token jest
    krótki. Dzięki temu "mp3", "rot13", "covid19" i długie ciągi base64 zostają nietknięte.
    """
    hits = 0

    def fix(m: re.Match) -> str:
        nonlocal hits
        tok = m.group(0)
        if not any(c.isalpha() for c in tok) or not any(c in _LEET_CHARS for c in tok):
            return tok
        if sum(1 for c in tok if c in _LEET_CHARS) > len(tok) / 2:
            return tok
        base = "".join(_LEET.get(c, c) for c in tok)
        for one in ("i", "l"):  # "1" bywa "i" albo "l"
            cand = base.replace("1", one)
            if cand.lower() in COMMON:
                hits += 1
                return cand
        if len(tok) <= 20 and _INTERIOR.search(tok):
            return base.replace("1", "i")
        return tok

    return _TOKEN.sub(fix, text), hits


_SPACED = re.compile(r"(?<![A-Za-z])[A-Za-z](?: {1,3}[A-Za-z](?![A-Za-z]))+")


def despace(text: str) -> tuple[str, int]:
    """Skleja "i g n o r e" w "ignore". Pojedyncza spacja łączy litery, dwie lub więcej oddzielają słowa."""
    runs = 0

    def fix(m: re.Match) -> str:
        nonlocal runs
        run = m.group(0)
        letters = [c for c in run if c != " "]
        if len(letters) < 4:
            return run
        runs += 1
        return re.sub(r"(?<=[A-Za-z]) (?=[A-Za-z])", "", run).replace("  ", " ")

    return _SPACED.sub(fix, text), runs


_COMMENT = re.compile(r"<!--(.*?)-->", re.DOTALL)
_ATTR = re.compile(r"""\b(?:alt|title|aria-label|content|value|placeholder)\s*=\s*(?:"([^"]*)"|'([^']*)')""", re.I)
_TAG = re.compile(r"<[^>]+>")
_MD_LINK = re.compile(r"!?\[([^\]]*)\]\(([^)]*)\)")
_HIDDEN = re.compile(
    r"display\s*:\s*none|visibility\s*:\s*hidden|font-size\s*:\s*0|opacity\s*:\s*0"
    r"|color\s*:\s*(?:#fff(?:fff)?\b|white\b)|aria-hidden",
    re.I,
)


def expose_markup(text: str) -> tuple[str, int, int]:
    """Wydobywa tekst ukryty w komentarzach, atrybutach i znacznikach. Zwraca (tekst, ukryte style, komentarze)."""
    if "<" not in text and "](" not in text:
        return text, 0, 0
    hidden = len(_HIDDEN.findall(text))
    comments = len(_COMMENT.findall(text))
    out = _COMMENT.sub(lambda m: " " + m.group(1) + " ", text)
    attrs = [a or b for a, b in _ATTR.findall(out)]
    out = _TAG.sub(" ", out)
    out = _MD_LINK.sub(lambda m: f" {m.group(1)} {m.group(2)} ", out)
    if attrs:
        out += " " + " ".join(attrs)
    return re.sub(r"[ \t]{2,}", " ", out).strip(), hidden, comments


_WORD = re.compile(r"[A-Za-z]{5,}")


def decase(text: str) -> tuple[str, int]:
    """Sprowadza do małych liter słowa o "poszarpanej" wielkości liter (iGnOrE, aLtErNaTiNg). Zwraca (tekst, liczba słów).

    Słowo zmieniamy, gdy co najmniej połowa przejść między sąsiednimi literami zmienia wielkość (w iGnOrE są 5 na 5).
    Zwykłe zapisy (iPhone: 1 z 5, McDonald: 2 z 7, CamelCase, WIKIPEDIA, Ignore) zostają nietknięte.
    """
    changed = 0

    def fix(m: re.Match) -> str:
        nonlocal changed
        w = m.group(0)
        flips = sum(1 for a, b in zip(w, w[1:]) if a.isupper() != b.isupper())
        if flips * 2 >= len(w) - 1:
            changed += 1
            return w.lower()
        return w

    return _WORD.sub(fix, text), changed
