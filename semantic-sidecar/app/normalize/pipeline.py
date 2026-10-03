"""Normalizacja tekstu przed klasyfikacją. Detektory dostają kilka wersji tekstu i biorą gorszy wynik.

Wersje: original, normalized (Unicode, bez znaków niewidocznych, bez homoglifów, encje HTML),
deobfuscated (leet, litery rozstrzelone, ukryty tekst z HTML), oraz odkodowane segmenty (base64, hex, ...).
"""

import html
import re
import unicodedata
from dataclasses import dataclass, field

from app.config import NormalizationConfig
from app.contract import Checkpoint
from app.normalize.decoders import DECODERS
from app.normalize.deobfuscate import deleet, despace, expose_markup
from app.normalize.unicode_clean import fold_homoglyphs, strip_invisible

_ENTITY = re.compile(r"&(?:#\d+|#x[0-9A-Fa-f]+|[A-Za-z]+);")


@dataclass
class Segment:
    kind: str
    text: str
    depth: int
    span: tuple[int, int] | None = None


@dataclass
class Signals:
    invisible_total: int = 0
    invisible_inside_words: int = 0
    tag_chars: int = 0
    homoglyph_words: int = 0
    html_entities: int = 0
    leet_tokens: int = 0
    spaced_runs: int = 0
    hidden_markup: int = 0
    html_comments: int = 0
    decoded_kinds: list[str] = field(default_factory=list)
    max_decode_depth: int = 0
    decode_limit_hit: bool = False
    error: bool = False


@dataclass
class Variant:
    name: str  # original | normalized | deobfuscated | decoded
    text: str
    detail: str = ""  # np. rodzaj dekodowania


@dataclass
class Normalized:
    original: str
    normalized: str
    deobfuscated: str | None
    decoded: list[Segment]
    signals: Signals

    @property
    def changed(self) -> bool:
        return self.normalized != self.original

    def variants(self) -> list[Variant]:
        out = [Variant("original", self.original)]
        seen = {self.original}
        for name, text, detail in (
            [("normalized", self.normalized, ""), ("deobfuscated", self.deobfuscated or "", "")]
            + [("decoded", s.text, s.kind) for s in self.decoded]
        ):
            if text and text not in seen:
                seen.add(text)
                out.append(Variant(name, text, detail))
        return out


def _decode_all(text: str, cfg: NormalizationConfig, sig: Signals) -> list[Segment]:
    segments: list[Segment] = []
    total = 0
    queue: list[tuple[str, int]] = [(text, 0)]
    enabled = [(k, fn) for k, fn in DECODERS.items() if getattr(cfg.decoders, k)]
    while queue:
        current, depth = queue.pop(0)
        if depth >= cfg.max_decode_depth:
            if any(fn(current) for _, fn in enabled):
                sig.decode_limit_hit = True
            continue
        for _, fn in enabled:
            for kind, decoded, span in fn(current):
                if len(segments) >= cfg.max_segments or total + len(decoded) > cfg.max_decoded_chars:
                    sig.decode_limit_hit = True
                    return segments
                total += len(decoded)
                segments.append(Segment(kind, decoded, depth + 1, span if depth == 0 else None))
                sig.max_decode_depth = max(sig.max_decode_depth, depth + 1)
                queue.append((decoded, depth + 1))
    return segments


def normalize(text: str, checkpoint: Checkpoint, cfg: NormalizationConfig) -> Normalized:
    """Nigdy nie rzuca wyjątku: przy błędzie zwraca sam oryginał i ustawia `signals.error`."""
    sig = Signals()
    if not cfg.enabled:
        return Normalized(text, text, None, [], sig)
    try:
        cleaned, inv = strip_invisible(text)
        sig.invisible_total, sig.invisible_inside_words, sig.tag_chars = inv.total, inv.inside_words, inv.tag_chars
        normalized = unicodedata.normalize("NFKC", cleaned)
        normalized, sig.homoglyph_words = fold_homoglyphs(normalized)
        entities = _ENTITY.findall(normalized)
        if entities:
            sig.html_entities = len(entities)
            normalized = html.unescape(normalized)

        decoded = _decode_all(normalized, cfg, sig)
        if cfg.decoders.unicode_tags and inv.smuggled:
            decoded.insert(0, Segment("unicode_tags", inv.smuggled, 1))
            sig.max_decode_depth = max(sig.max_decode_depth, 1)
        sig.decoded_kinds = sorted({s.kind for s in decoded})

        deob = normalized
        if cfg.deobfuscate.markup:
            deob, sig.hidden_markup, sig.html_comments = expose_markup(deob)
        if cfg.deobfuscate.spaced:
            deob, sig.spaced_runs = despace(deob)
        if cfg.deobfuscate.leet:
            deob, sig.leet_tokens = deleet(deob)
        return Normalized(text, normalized, deob if deob != normalized else None, decoded, sig)
    except Exception:  # normalizacja nie może wywrócić sidecara
        sig.error = True
        return Normalized(text, text, None, [], sig)
