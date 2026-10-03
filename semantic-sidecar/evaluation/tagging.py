"""Tagi przypadków wyliczane z tekstu. `noisy`: tekst zaszumiony (homoglify, leet, znaki nieASCII, losowa interpunkcja)."""

import re

from app.config import NormalizationConfig
from app.contract import Checkpoint
from app.normalize import normalize

_STRAY = re.compile(r"\s[_,:;+-]\s")


def is_noisy(text: str, checkpoint: Checkpoint = Checkpoint.P1) -> bool:
    s = normalize(text, checkpoint, NormalizationConfig()).signals
    return (
        s.homoglyph_words > 0 or s.leet_tokens > 0 or s.invisible_total > 0
        or any(ord(c) > 127 and c.isalpha() for c in text)
        or len(_STRAY.findall(text)) >= 2
    )
