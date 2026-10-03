# Sidecar semantyczny (Python/FastAPI + modele HF na CPU). Buduj z katalogu semantic-sidecar/.
# Docelowo działa na Raspberry Pi (arm64), ale obraz buduje się też na x86_64.
#
# Wagi modeli NIE są w obrazie (setki MB): pobiera je usługa semantic-sidecar-init (docker-compose.yml) do wolumenu.
# Dla pracy offline zbuduj obraz z modelami w środku:  docker build --build-arg BAKE_MODELS=1 -t semantic-sidecar .

FROM python:3.12-slim AS build
ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
WORKDIR /build
RUN python -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH

COPY constraints.txt pyproject.toml ./
# Koło CPU z oficjalnego indeksu PyTorcha. Domyślne koło z PyPI na x86_64 ciągnie biblioteki CUDA (ok. 2,5 GB).
RUN pip install -c constraints.txt torch --index-url https://download.pytorch.org/whl/cpu
# Reszta zależności prosto z pyproject.toml (zależności projektu + extra `serve`), żeby lista nie rozjechała się z projektem.
RUN python -c "import tomllib; p = tomllib.load(open('pyproject.toml', 'rb'))['project']; print('\n'.join(p['dependencies'] + p['optional-dependencies']['serve']))" > requirements.txt \
    && pip install -c constraints.txt -r requirements.txt


FROM python:3.12-slim
RUN useradd --system --create-home --uid 10001 app
WORKDIR /app
COPY --from=build /opt/venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TOKENIZERS_PARALLELISM=false \
    HF_HOME=/tmp/hf \
    SEMANTIC_CONFIG=config/semantic.models.yaml

# Aplikacja uruchamiana ze źródeł (nie jako zainstalowany pakiet): ścieżki modeli i konfiguracji liczą się względem /app.
COPY app app
COPY scripts scripts
COPY config config
RUN mkdir -p models && chown app:app models

ARG BAKE_MODELS=0
RUN if [ "$BAKE_MODELS" = "1" ]; then \
        python -m scripts.fetch_models --only injection_classifier_protectai && chown -R app:app models; \
    fi

USER app
EXPOSE 8001
# Jeden proces: modele są duże, a równoległość daje pula wątków w aplikacji. Zmienna OMP_NUM_THREADS ogranicza wątki PyTorcha.
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001", "--workers", "1"]
