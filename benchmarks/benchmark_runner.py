"""Benchmark runner for VERA model components.

This script measures latency and throughput for LLM, embeddings, and STT.
Use a config file to point at local model files.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, List, Optional


@dataclass
class BenchResult:
    name: str
    samples_ms: List[float]
    notes: str = ""

    @property
    def p50_ms(self) -> float:
        return statistics.median(self.samples_ms) if self.samples_ms else 0.0

    @property
    def p95_ms(self) -> float:
        if not self.samples_ms:
            return 0.0
        sorted_samples = sorted(self.samples_ms)
        index = max(int(0.95 * len(sorted_samples)) - 1, 0)
        return sorted_samples[index]


def time_it_ms(fn: Callable[[], None], iterations: int) -> List[float]:
    samples: List[float] = []
    for _ in range(iterations):
        start = time.perf_counter()
        fn()
        end = time.perf_counter()
        samples.append((end - start) * 1000.0)
    return samples


def print_result(result: BenchResult) -> None:
    avg_ms = statistics.mean(result.samples_ms) if result.samples_ms else 0.0
    print(f"\n[{result.name}]")
    print(f"  samples: {len(result.samples_ms)}")
    print(f"  p50: {result.p50_ms:.1f} ms")
    print(f"  p95: {result.p95_ms:.1f} ms")
    print(f"  avg: {avg_ms:.1f} ms")
    if result.notes:
        print(f"  notes: {result.notes}")


def bench_llm(iterations: int) -> BenchResult:
    """Benchmark LLM latency and tokens/sec.
    """
    try:
        from llama_cpp import Llama
    except ImportError as exc:
        return BenchResult(
            name="LLM",
            samples_ms=[],
            notes=f"Missing dependency: {exc}. Install llama-cpp-python.",
        )

    model_path = get_config_value("llm.model_path")
    if not model_path:
        return BenchResult(name="LLM", samples_ms=[], notes="Missing llm.model_path in config")

    n_ctx = int(get_config_value("llm.n_ctx") or 2048)
    n_threads = int(get_config_value("llm.n_threads") or os.cpu_count() or 4)
    max_tokens = int(get_config_value("llm.max_tokens") or 128)
    temperature = float(get_config_value("llm.temperature") or 0.2)

    llm = Llama(
        model_path=str(model_path),
        n_ctx=n_ctx,
        n_threads=n_threads,
        verbose=False,
    )

    prompt = "You are VERA. Reply in 2 short sentences."

    def run_once() -> None:
        llm.create_chat_completion(
            messages=[{"role": "user", "content": prompt}],
            max_tokens=max_tokens,
            temperature=temperature,
        )

    samples = time_it_ms(run_once, iterations)
    return BenchResult(name="LLM", samples_ms=samples)


def bench_embeddings(iterations: int, batch_size: int) -> BenchResult:
    """Benchmark embedding latency for a given batch size."""
    try:
        from sentence_transformers import SentenceTransformer
    except ImportError as exc:
        return BenchResult(
            name="Embeddings",
            samples_ms=[],
            notes=f"Missing dependency: {exc}. Install sentence-transformers.",
        )

    model_name = get_config_value("embeddings.model_name") or "intfloat/multilingual-e5-small"
    model = SentenceTransformer(model_name)

    texts = [
        "Summarize this email about the meeting next week.",
        "Summarize this message about project planning tasks.",
        "Create a short to-do list for a weekly review.",
        "Draft a short reply to confirm the meeting time.",
    ]
    batch = (texts * (batch_size // len(texts) + 1))[:batch_size]

    def run_once() -> None:
        model.encode(batch, normalize_embeddings=True)

    samples = time_it_ms(run_once, iterations)
    return BenchResult(
        name=f"Embeddings (batch={batch_size})",
        samples_ms=samples,
    )


def bench_stt(iterations: int, seconds: int) -> BenchResult:
    """Benchmark STT latency for a fixed audio length."""
    try:
        from faster_whisper import WhisperModel
    except ImportError as exc:
        return BenchResult(
            name="STT",
            samples_ms=[],
            notes=f"Missing dependency: {exc}. Install faster-whisper.",
        )

    model_size = get_config_value("stt.model_size") or "base"
    device = get_config_value("stt.device") or "cpu"
    compute_type = get_config_value("stt.compute_type") or "int8"
    audio_path = get_config_value(f"stt.audio_{seconds}s")
    if not audio_path:
        return BenchResult(name="STT", samples_ms=[], notes=f"Missing stt.audio_{seconds}s in config")

    stt = WhisperModel(model_size, device=device, compute_type=compute_type)

    def run_once() -> None:
        segments, _info = stt.transcribe(str(audio_path))
        for _ in segments:
            pass

    samples = time_it_ms(run_once, iterations)
    return BenchResult(
        name=f"STT (audio={seconds}s)",
        samples_ms=samples,
    )


CONFIG: dict = {}


def load_config(path: Optional[str]) -> dict:
    if not path:
        return {}
    config_path = Path(path)
    if not config_path.exists():
        raise FileNotFoundError(f"Config not found: {config_path}")
    return json.loads(config_path.read_text(encoding="utf-8"))


def get_config_value(key: str) -> Optional[str]:
    parts = key.split(".")
    current: object = CONFIG
    for part in parts:
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    if current is None:
        return None
    return str(current)


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="VERA benchmarking skeleton")
    parser.add_argument("--config", type=str, default="benchmarks/config.json")
    parser.add_argument("--iterations", type=int, default=5)
    parser.add_argument("--embed-batch", type=int, default=8)
    parser.add_argument("--stt-seconds", type=int, default=15)
    args = parser.parse_args(argv)

    global CONFIG
    try:
        CONFIG = load_config(args.config)
    except FileNotFoundError:
        CONFIG = {}

    print("Running benchmarks...")
    print_result(bench_llm(args.iterations))
    print_result(bench_embeddings(args.iterations, args.embed_batch))
    print_result(bench_stt(args.iterations, args.stt_seconds))
    print("\nDone.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
