"""Detektor obfuskacji: zamienia sygnały normalizacji na wynik 0-1.

To reguły, nie model. Wynik mówi "tekst jest zapisany tak, by utrudnić czytanie", a nie "to jest atak", więc ma
służyć jako jeden z sygnałów w agregatorze. Wagi są konfigurowalne (`detectors.obfuscation.params.weights`).
"""

from app.contract import Checkpoint, ClassifyRequest, Finding, RawEvidence
from app.detectors.base import Detector
from app.normalize import Normalized

DEFAULT_WEIGHTS: dict[str, float] = {
    "unicode_tags": 0.95,       # ukryty tekst w znakach tagów prawie nigdy nie jest uprawniony
    "nested_encoding": 0.85,    # kodowanie w kodowaniu
    "homoglyph": 0.85,          # słowo mieszające łacinę z cyrylicą/grecką
    "decode_limit": 0.80,       # przekroczono limity dekodowania (próba wyczerpania zasobów)
    "invisible_in_word": 0.70,  # znak niewidoczny wewnątrz słowa
    "encoded_text": 0.60,       # czytelny tekst ukryty w base64, hex, rot13, odwrócony, url
    "spaced_letters": 0.60,
    "leet": 0.60,
    "hidden_markup": 0.60,      # ukryty styl HTML (np. biały tekst, display:none)
    "html_entities": 0.50,      # co najmniej 3 encje HTML w zwykłym tekście
}


def _snippet(text: str, limit: int = 80) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


class ObfuscationDetector(Detector):
    name = "obfuscation"
    version = "0.1.0"
    checkpoints = frozenset(Checkpoint)
    requires_normalization_signals = True  # działa tylko w trybie samodzielnym (input.pre_normalized: false)

    def __init__(self, weights: dict[str, float] | None = None):
        self.weights = {**DEFAULT_WEIGHTS, **(weights or {})}

    def run(self, req: ClassifyRequest, norm: Normalized) -> Finding:
        s = norm.signals
        w = self.weights
        found: list[tuple[float, str, RawEvidence | None]] = []

        def add(label: str, evidence: RawEvidence | None = None) -> None:
            found.append((w[label], label, evidence))

        for seg in norm.decoded:
            if seg.kind == "unicode_tags":
                add("unicode_tags", RawEvidence(text=_snippet(seg.text), variant="decoded"))
        if s.max_decode_depth >= 2:
            add("nested_encoding")
        if s.homoglyph_words:
            add("homoglyph", RawEvidence(text=_snippet(norm.normalized), variant="normalized"))
        if s.decode_limit_hit:
            add("decode_limit")
        if s.invisible_inside_words:
            add("invisible_in_word")
        for seg in norm.decoded:
            if seg.kind in ("base64", "hex", "rot13", "reversed", "url"):
                add("encoded_text", RawEvidence(text=_snippet(seg.text), variant="decoded", span=seg.span))
                break
        if s.spaced_runs:
            add("spaced_letters", RawEvidence(text=_snippet(norm.deobfuscated or ""), variant="deobfuscated"))
        if s.leet_tokens >= 2:
            add("leet", RawEvidence(text=_snippet(norm.deobfuscated or ""), variant="deobfuscated"))
        if s.hidden_markup:
            add("hidden_markup")
        if s.html_entities >= 3:
            add("html_entities")

        if not found:
            return Finding(score=0.0)
        score, label, evidence = max(found, key=lambda f: f[0])
        return Finding(score=score, label=label, evidence=evidence)
