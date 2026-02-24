# Benchmarks

This folder contains a lightweight benchmark skeleton for validating VERA model performance on the target server.

## Install

```
pip install -r benchmarks/requirements.txt
```

## Configure

- Copy `benchmarks/config.example.json` to `benchmarks/config.json`
- Update model paths and audio file locations

## Quick run

```
python benchmarks/benchmark_runner.py --iterations 5 --embed-batch 8 --stt-seconds 15
```

## Notes

- LLM expects a GGUF quantized model path (for `llama-cpp-python`).
- Embeddings use `sentence-transformers` and will download models on first run.
- STT uses `faster-whisper` with local audio files for reproducible timing.

## Suggested sample audio

- 15s and 60s clips in Swedish and English.
- Keep samples in a local `benchmarks/audio/` folder (not committed).
