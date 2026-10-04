"""Normalizacja tekstu przed klasyfikacją. Detektory dostają kilka wersji tekstu i biorą gorszy wynik.

Wersje: original, normalized (Unicode, bez znaków niewidocznych, bez homoglifów, encje HTML),
deobfuscated (leet, litery rozstrzelone, ukryty tekst z HTML), odkodowane segmenty (base64, hex, ...) oraz expanded.

`expanded` to tekst, w którym zakodowane fragmenty są podmienione na swoją (najgłębiej) odkodowaną treść. Gdy istnieje, zastępuje
`original` i `normalized` na liście wariantów: zakodowany ciąg znaków sam w sobie wygląda dla klasyfikatora podejrzanie, więc ocena
oryginału z blobem blokowałaby niewinne zakodowane teksty, a maksimum po wariantach nigdy by tego nie naprawiło.
"""

import html
import re
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field

from app.config import NormalizationConfig
from app.contract import Checkpoint
from app.normalize.decoders import DECODERS
from app.normalize.deobfuscate import decase, deleet, despace, expose_markup
from app.normalize.unicode_clean import fold_homoglyphs, strip_invisible

_ENTITY = re.compile(r"&(?:#\d+|#x[0-9A-Fa-f]+|[A-Za-z]+);")


@dataclass
class Segment:
    kind: str
    text: str
    depth: int
    span: tuple[int, int] | None = None
    parent: int | None = None  # indeks segmentu, z którego odkodowano ten (łańcuch kodowań)
    intermediate: bool = False  # z tego segmentu odkodowano coś głębiej: sam jest tylko pośrednim blobem i nie jest oceniany osobno


@dataclass
class Signals:
    invisible_total: int = 0
    invisible_inside_words: int = 0
    tag_chars: int = 0
    homoglyph_words: int = 0
    html_entities: int = 0
    leet_tokens: int = 0
    spaced_runs: int = 0
    alt_case_words: int = 0
    hidden_markup: int = 0
    html_comments: int = 0
    decoded_kinds: list[str] = field(default_factory=list)
    max_decode_depth: int = 0
    decode_limit_hit: bool = False
    error: bool = False


@dataclass
class Variant:
    name: str  # original | normalized | deobfuscated | decoded | expanded
    text: str
    detail: str = ""  # np. rodzaj dekodowania


@dataclass
class Normalized:
    original: str
    normalized: str
    deobfuscated: str | None
    decoded: list[Segment]
    signals: Signals
    expanded: str | None = None  # tekst z odkodowanymi fragmentami w miejscu zakodowanych (None, gdy nic nie odkodowano)

    @property
    def changed(self) -> bool:
        return self.normalized != self.original

    def variants(self) -> list[Variant]:
        if self.expanded:  # oryginał i normalized zawierają zakodowane bloby, więc nie oceniamy ich osobno
            head = [("expanded", self.expanded, ""), ("deobfuscated", self.deobfuscated or "", "")]
        else:
            head = [("original", self.original, ""), ("normalized", self.normalized, ""), ("deobfuscated", self.deobfuscated or "", "")]
        out: list[Variant] = []
        seen: set[str] = set()
        for name, text, detail in head + [("decoded", s.text, s.kind) for s in self.decoded if not s.intermediate]:
            if text and text not in seen:
                seen.add(text)
                out.append(Variant(name, text, detail))
        return out or [Variant("original", self.original)]  # pusty tekst też musi mieć wariant do oceny


def _expand(text: str, segments: list[Segment]) -> str | None:
    """Podmienia zakodowane fragmenty pierwszego poziomu na ich najgłębszą odkodowaną treść. None, gdy nic nie podmieniono."""
    children: dict[int, list[int]] = defaultdict(list)
    for i, seg in enumerate(segments):
        if seg.parent is not None:
            children[seg.parent].append(i)

    def leaf(i: int) -> str:
        kids = children.get(i)
        return leaf(kids[0]) if kids else segments[i].text

    reps = sorted((seg.span, leaf(i)) for i, seg in enumerate(segments) if seg.depth == 1 and seg.span)
    out, pos, replaced = [], 0, False
    for (a, b), repl in reps:
        if a < pos:  # zachodzące na siebie fragmenty: pierwszy wygrywa
            continue
        out += [text[pos:a], repl]
        pos, replaced = b, True
    out.append(text[pos:])
    return "".join(out) if replaced else None


def _decode_all(text: str, cfg: NormalizationConfig, sig: Signals) -> list[Segment]:
    segments: list[Segment] = []
    total = 0
    queue: list[tuple[str, int, int | None]] = [(text, 0, None)]
    enabled = [(k, fn) for k, fn in DECODERS.items() if getattr(cfg.decoders, k)]
    while queue:
        current, depth, parent = queue.pop(0)
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
                segments.append(Segment(kind, decoded, depth + 1, span if depth == 0 else None, parent))
                sig.max_decode_depth = max(sig.max_decode_depth, depth + 1)
                queue.append((decoded, depth + 1, len(segments) - 1))
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

        for seg in decoded:
            if seg.parent is not None:
                decoded[seg.parent].intermediate = True
        expanded = _expand(normalized, decoded)
        base = expanded or normalized
        deob = base
        if cfg.deobfuscate.markup:
            deob, sig.hidden_markup, sig.html_comments = expose_markup(deob)
        if cfg.deobfuscate.spaced:
            deob, sig.spaced_runs = despace(deob)
        if cfg.deobfuscate.leet:
            deob, sig.leet_tokens = deleet(deob)
        if cfg.deobfuscate.case:
            deob, sig.alt_case_words = decase(deob)
        return Normalized(text, normalized, deob if deob != base else None, decoded, sig, expanded)
    except Exception:  # normalizacja nie może wywrócić sidecara
        sig.error = True
        return Normalized(text, text, None, [], sig)
