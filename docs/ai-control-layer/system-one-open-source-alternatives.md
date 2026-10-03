# Open-source alternatives to TypeSafe Jev

## Recommendation: Laya

**[Laya](https://github.com/NandhaKishorM/laya)** is the closest commercially usable open-source replacement for TypeSafe's Jev:

- Apache-2.0 code and weights
- Non-autoregressive, non-generative inference
- Typed `choice`, `score`, and `noul` decisions with probabilities
- Answers multiple questions in one forward pass
- Jev-compatible `POST /v1/systemone` API
- 421M English and 322M multilingual checkpoints
- Local CPU/GPU deployment, ONNX, Docker, TypeScript, MCP, and LangGraph support
- Publisher-reported latency around 33–40 ms on a T4

### Important limitations

Laya is primarily a **fast model to specialize**, not a reliable universal zero-shot replacement. Its own benchmarks disclose:

- Base checkpoint: **0.362 accuracy** on typed-decisions, below the **0.461 majority baseline**
- Fine-tuned checkpoint: **0.766 accuracy**
- Shipped probabilities can be overconfident; calibration should be refitted on deployment data
- Accuracy deteriorates with roughly 20+ choices; Banking77 was **0.425**, versus Jev's published **0.870**

Therefore:

> Use Laya with domain fine-tuning, held-out calibration, and a fallback for low-confidence decisions.

### Quick start

```bash
pip install "laya[serve]"

LAYA_DEVICE=cuda \
LAYA_PRELOAD=1 \
laya-serve
```

Point an existing Jev client at:

```text
http://localhost:8000/v1/systemone
```

## Alternatives

| Project | Best for | Main drawback |
|---|---|---|
| **[Laya](https://github.com/NandhaKishorM/laya)** | Closest fully open Jev replacement; lightweight and multilingual | Needs task-specific evaluation and usually fine-tuning |
| **[CLM-8B](https://github.com/Contrastive-LM/CLM)** | Agent action ranking, tool selection, and best-of-N verification | Requires a Qwen3-8B embedding backend and typically a GPU; strongest results are fine-tuned |
| **[jevos](https://github.com/feder-cr/jev)** | Simple CPU deployment; publisher reports 25–110 ms laptop latency | Primarily optimized around yes/no decisions; model-weight licensing should be verified separately |
| **[GLiNER2.5](https://github.com/fastino-ai/GLiNER2)** | Structured extraction combined with classification | Not Jev API-compatible, and confidence scores are not necessarily calibrated probabilities |
| **[OpenJev](https://huggingface.co/AlquimiaAi/openjev)** | Highest reported Jev-like accuracy and multimodal agent decisions | Weights are CC BY-NC 4.0, so it is not strict open source and cannot be used commercially without permission |

## Verdict by use case

- **Production/commercial, strict open source:** Laya
- **Action ranking with GPU capacity:** CLM-8B
- **Research-only, strongest reported Jev parity:** OpenJev
- **CPU-only appliance:** jevos

## Semantic caution

“Can't hallucinate” in this model category means the model cannot emit values outside the declared schema. It can still return a perfectly valid—but incorrect—decision with high confidence.

## Sources

- [Introducing System One Models & Jev — TypeSafe](https://typesafe.ai/blog/introducing-system-one-models-and-jev)
- [Laya repository](https://github.com/NandhaKishorM/laya)
- [Laya model card](https://huggingface.co/convaiinnovations/laya)
- [Laya benchmarks](https://github.com/NandhaKishorM/laya/blob/main/BENCHMARKS.md)
- [CLM repository](https://github.com/Contrastive-LM/CLM)
- [CLM-8B model card](https://huggingface.co/Contrastive-LM/CLM-v0.1-8B)
- [jevos repository](https://github.com/feder-cr/jev)
- [GLiNER2 repository](https://github.com/fastino-ai/GLiNER2)
- [OpenJev model card](https://huggingface.co/AlquimiaAi/openjev)
