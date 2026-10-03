"""Dekodowanie ukrytych ładunków. Każdy dekoder zwraca (rodzaj, odkodowany tekst, zakres w tekście wejściowym)."""

import base64
import binascii
import codecs
import re
from urllib.parse import unquote

from app.normalize.wordlist import COMMON

Found = tuple[str, str, tuple[int, int]]

_B64 = re.compile(r"(?<![A-Za-z0-9+/_=-])[A-Za-z0-9+/_-]{16,}={0,2}(?![A-Za-z0-9+/_=-])")
_HEX = re.compile(r"(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}){8,}(?![0-9A-Fa-f])")
_HEX_SEP = re.compile(r"(?<![0-9A-Fa-f])(?:[0-9A-Fa-f]{2}[ :-]){7,}[0-9A-Fa-f]{2}(?![0-9A-Fa-f])")
_PERCENT = re.compile(r"(?:%[0-9A-Fa-f]{2}){3,}")
_WORD = re.compile(r"[A-Za-z]+")


def _printable_text(raw: bytes) -> str | None:
    """Zwraca tekst tylko jeśli bajty to czytelny UTF-8. Odcina losowe ciągi, hashe i dane binarne."""
    try:
        s = raw.decode("utf-8")
    except UnicodeDecodeError:
        return None
    if len(s) < 6:
        return None
    printable = sum(1 for c in s if c.isprintable() or c in "\n\t\r")
    textual = sum(1 for c in s if c.isalpha() or c.isspace())
    if printable / len(s) < 0.95 or textual / len(s) < 0.6:
        return None
    return s


def find_base64(text: str) -> list[Found]:
    out: list[Found] = []
    for m in _B64.finditer(text):
        token = m.group(0).rstrip("=").replace("-", "+").replace("_", "/")
        token += "=" * (-len(token) % 4)
        try:
            raw = base64.b64decode(token, validate=True)
        except (binascii.Error, ValueError):
            continue
        decoded = _printable_text(raw)
        if decoded:
            out.append(("base64", decoded, m.span()))
    return out


def find_hex(text: str) -> list[Found]:
    out: list[Found] = []
    for rx in (_HEX, _HEX_SEP):
        for m in rx.finditer(text):
            try:
                raw = bytes.fromhex(re.sub(r"[ :-]", "", m.group(0)))
            except ValueError:
                continue
            decoded = _printable_text(raw)
            if decoded:
                out.append(("hex", decoded, m.span()))
    return out


def find_url_encoding(text: str) -> list[Found]:
    """Dekoduje percent-kodowanie, ale tylko gdy zakodowano głównie bajty ASCII.

    Atak koduje angielski tekst, a więc bajty ASCII. Percent-kodowanie znaków spoza ASCII (np. chińskie znaki
    w adresie URL) jest zwyczajne i nie jest ukrywaniem treści.
    """
    groups = [int(g[1:], 16) for m in _PERCENT.finditer(text) for g in re.findall(r"%[0-9A-Fa-f]{2}", m.group(0))]
    if not groups or sum(1 for b in groups if b < 0x80) / len(groups) < 0.5:
        return []
    decoded = unquote(text)
    return [("url", decoded, (0, len(text)))] if decoded != text else []


def _runs(text: str, mapped) -> list[tuple[int, int]]:
    """Ciągi co najmniej 3 kolejnych słów oznaczonych przez `mapped`, rozdzielonych tylko białymi znakami."""
    marked = [(m.start(), m.end()) for m in _WORD.finditer(text) if mapped(m.group(0))]
    spans: list[tuple[int, int]] = []
    run: list[tuple[int, int]] = []
    for s, e in marked:
        if run and text[run[-1][1]:s].strip() == "" and len(text[run[-1][1]:s]) <= 3:
            run.append((s, e))
        else:
            if len(run) >= 3:
                spans.append((run[0][0], run[-1][1]))
            run = [(s, e)]
    if len(run) >= 3:
        spans.append((run[0][0], run[-1][1]))
    return spans


def find_rot13(text: str) -> list[Found]:
    def is_rot(w: str) -> bool:
        lw = w.lower()
        return len(lw) >= 2 and lw not in COMMON and codecs.encode(lw, "rot13") in COMMON

    return [("rot13", codecs.encode(text[s:e], "rot13"), (s, e)) for s, e in _runs(text, is_rot)]


def find_reversed(text: str) -> list[Found]:
    def is_rev(w: str) -> bool:
        lw = w.lower()
        return len(lw) >= 3 and lw not in COMMON and lw[::-1] in COMMON

    # Odwrócony tekst: każde słowo jest odwrócone i kolejność słów też, więc odwracamy cały ciąg.
    out: list[Found] = []
    for s, e in _runs(text, is_rev):
        out.append(("reversed", text[s:e][::-1], (s, e)))
    return out


DECODERS = {
    "base64": find_base64,
    "hex": find_hex,
    "url": find_url_encoding,
    "rot13": find_rot13,
    "reversed": find_reversed,
}
