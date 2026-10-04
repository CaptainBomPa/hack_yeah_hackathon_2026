"""Logowanie sidecara: strukturalne (JSON) albo tekstowe, z identyfikatorem żądania.

ZASADA: do logów nigdy nie trafia treść promptu ani fragmenty tekstu (mogą zawierać PII i sekrety, także odkodowane z base64).
Logujemy tylko metadane: długość, skrót HMAC (zob. app/evidence.py), wyniki i czasy detektorów, statusy.
Treść wyjątków też nie jest logowana (może zawierać fragment wejścia); zapisujemy sam typ.

Ustawienia z środowiska: SEMANTIC_LOG_LEVEL (domyślnie INFO), SEMANTIC_LOG_FORMAT (`json` domyślnie albo `text`).
"""

import contextvars
import json
import logging
import os
import sys
import time

request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="-")

_RESERVED = set(vars(logging.LogRecord("", 0, "", 0, "", (), None))) | {"message", "asctime", "request_id", "ctx"}
_NOISY = ("transformers", "urllib3", "httpx", "httpcore", "huggingface_hub", "filelock", "torch")


class RequestIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get()
        return True


def _fields(record: logging.LogRecord) -> dict:
    out = dict(getattr(record, "ctx", None) or {})
    out.update({k: v for k, v in vars(record).items() if k not in _RESERVED and not k.startswith("_")})
    return out


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "ts": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)) + f".{int(record.msecs):03d}Z",
            "level": record.levelname,
            "logger": record.name,
            "request_id": getattr(record, "request_id", "-"),
            "msg": record.getMessage(),
            **_fields(record),
        }
        return json.dumps(entry, ensure_ascii=False, default=str)


class TextFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        extra = " ".join(f"{k}={json.dumps(v, ensure_ascii=False, default=str)}" for k, v in _fields(record).items())
        ts = time.strftime("%H:%M:%S", time.gmtime(record.created)) + f".{int(record.msecs):03d}"
        return f"{ts} {record.levelname:7} {record.name} [{getattr(record, 'request_id', '-')}] {record.getMessage()} {extra}".rstrip()


_HANDLER_FLAG = "_semantic_sidecar_handler"


def configure_logging(level: str | None = None, fmt: str | None = None) -> None:
    """Idempotentna konfiguracja logowania procesu (można wywołać wielokrotnie, np. w testach)."""
    level = (level or os.environ.get("SEMANTIC_LOG_LEVEL") or "INFO").upper()
    fmt = (fmt or os.environ.get("SEMANTIC_LOG_FORMAT") or "json").lower()
    if level not in logging.getLevelNamesMapping():
        level = "INFO"
    root = logging.getLogger()
    root.setLevel(level)
    handler = next((h for h in root.handlers if getattr(h, _HANDLER_FLAG, False)), None)
    if handler is None:
        handler = logging.StreamHandler(sys.stdout)
        setattr(handler, _HANDLER_FLAG, True)
        handler.addFilter(RequestIdFilter())
        root.addHandler(handler)
    handler.setFormatter(JsonFormatter() if fmt == "json" else TextFormatter())
    for name in _NOISY:
        logging.getLogger(name).setLevel(logging.WARNING)
