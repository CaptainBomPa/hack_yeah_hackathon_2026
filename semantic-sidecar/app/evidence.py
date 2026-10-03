"""Zamiana surowego dowodu detektora na dowód bezpieczny do logów i odpowiedzi.

Fragment tekstu może zawierać sekrety, także odkodowane z base64 (np. klucz API ukryty w zakodowanym tekście).
Dlatego publiczny `Evidence` nie ma treści: tylko długość, zakres i skrót HMAC. Zwykły hash krótkiego sekretu
dałoby się odgadywać słownikiem, a HMAC z kluczem nie.
"""

import hashlib
import hmac
import logging
import os
import re
import secrets

from app.config import EvidenceConfig
from app.contract import Evidence, RawEvidence

log = logging.getLogger(__name__)
_TOKEN = re.compile(r"\S+")


def mask(text: str, limit: int = 80) -> str:
    """Zamaskowany podgląd, bezpieczny z konstrukcji: z każdego słowa zostają co najwyżej 2 pierwsze znaki."""

    def m(match: re.Match) -> str:
        w = match.group(0)
        return w if len(w) <= 2 else w[:2] + "*" * (len(w) - 2)

    out = _TOKEN.sub(m, " ".join(text.split()))
    return out if len(out) <= limit else out[: limit - 1] + "…"


class EvidenceSanitizer:
    def __init__(self, key: bytes, include_preview: bool = False):
        self._key = key
        self._include_preview = include_preview

    @classmethod
    def from_config(cls, cfg: EvidenceConfig) -> "EvidenceSanitizer":
        env = os.environ.get(cfg.digest_key_env)
        if env:
            key = env.encode()
        else:
            key = secrets.token_bytes(32)
            log.warning("Brak %s: skróty dowodów są losowe i zmienią się po restarcie.", cfg.digest_key_env)
        return cls(key, cfg.include_preview)

    def sanitize(self, raw: RawEvidence | None) -> Evidence | None:
        if raw is None:
            return None
        digest = hmac.new(self._key, raw.text.encode("utf-8"), hashlib.sha256).hexdigest()[:12]
        return Evidence(
            variant=raw.variant,
            span=raw.span,
            length=len(raw.text),
            digest=digest,
            preview=mask(raw.text) if self._include_preview else None,
        )
