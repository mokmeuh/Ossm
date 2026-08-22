# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy>=2.3", "typer>=0.16"]
# ///
# --- How to run ---
# uv run firmware/Software/tools/characterize_signal.py CAPTURE --output-dir OUTPUT

from __future__ import annotations

import csv
import json
from dataclasses import dataclass
from itertools import pairwise
from pathlib import Path
from typing import Annotated, Final, TypedDict

import numpy.typing as npt
import numpy as np
import typer


FIELDS: Final = (
    "timestamp_ms",
    "raw_adc",
    "offset_adc",
    "direction",
    "command_speed_millihz",
    "command_acceleration",
    "command_position",
    "saturated",
    "timestamp_monotonic",
)
LABEL_FIELDS: Final = (
    "start_ms",
    "end_ms",
    "direction",
    "load_level",
    "verified_external_load",
)
BAND_FIELDS: Final = (
    "direction",
    "command_band",
    "sample_count",
    "signal_median_adc",
    "noise_std_adc",
    "cv",
    "snr_vs_baseline",
    "saturation_rate",
)


class BandRow(TypedDict):
    direction: int
    command_band: str
    sample_count: int
    signal_median_adc: float
    noise_std_adc: float
    cv: float | None
    snr_vs_baseline: float | None
    saturation_rate: float


@dataclass(frozen=True, slots=True)
class LoadLabel:
    start_ms: int
    end_ms: int
    direction: int
    level: float


def read_capture(path: Path) -> npt.NDArray[np.float64]:
    rows: list[list[float]] = []
    header_seen = False
    with path.open(encoding="utf-8", errors="replace", newline="") as stream:
        for raw_line in stream:
            line = raw_line.strip()
            if line == ",".join(FIELDS):
                header_seen = True
                continue
            if not header_seen:
                continue
            parts = line.split(",")
            if len(parts) != len(FIELDS):
                continue
            try:
                rows.append([float(part) for part in parts])
            except ValueError:
                continue
    if not rows:
        raise typer.BadParameter("capture has no diagnostic samples")
    return np.asarray(rows, dtype=np.float64)


def read_labels(path: Path | None) -> list[LoadLabel]:
    if path is None:
        return []
    labels: list[LoadLabel] = []
    with path.open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        if tuple(reader.fieldnames or ()) != LABEL_FIELDS:
            raise typer.BadParameter("labels schema is invalid")
        for row in reader:
            if row["verified_external_load"].strip().lower() != "true":
                continue
            labels.append(
                LoadLabel(
                    start_ms=int(row["start_ms"]),
                    end_ms=int(row["end_ms"]),
                    direction=int(row["direction"]),
                    level=float(row["load_level"]),
                ),
            )
    return labels


def safe_round(value: float) -> float:
    return round(value, 6)


def optional_ratio(numerator: float, denominator: float) -> float | None:
    if denominator == 0:
        return None
    return safe_round(numerator / denominator)


def command_bands(samples: npt.NDArray[np.float64]) -> npt.NDArray[np.str_]:
    speed = np.abs(samples[:, 4])
    nonzero = speed[speed > 0]
    bands = np.full(len(speed), "idle", dtype="U4")
    if len(nonzero) == 0:
        return bands
    low, high = np.quantile(nonzero, (1 / 3, 2 / 3))
    bands[(speed > 0) & (speed <= low)] = "low"
    bands[(speed > low) & (speed <= high)] = "mid"
    bands[speed > high] = "high"
    return bands


def aggregate_bands(
    samples: npt.NDArray[np.float64],
    signal: npt.NDArray[np.float64],
    baseline_median: float,
    baseline_noise: float,
) -> list[BandRow]:
    bands = command_bands(samples)
    output: list[BandRow] = []
    keys = sorted({(int(row[3]), str(band)) for row, band in zip(samples, bands, strict=True)})
    for direction, band in keys:
        mask = (samples[:, 3] == direction) & (bands == band)
        values = signal[mask]
        median = float(np.median(values))
        std = float(np.std(values, ddof=1)) if len(values) > 1 else 0.0
        mean = float(np.mean(values))
        output.append(
            BandRow(
                direction=direction,
                command_band=band,
                sample_count=len(values),
                signal_median_adc=safe_round(median),
                noise_std_adc=safe_round(std),
                cv=optional_ratio(std, abs(mean)),
                snr_vs_baseline=optional_ratio(abs(median - baseline_median), baseline_noise),
                saturation_rate=safe_round(float(np.mean(samples[mask, 7]))),
            ),
        )
    return output


def known_load_metrics(
    samples: npt.NDArray[np.float64],
    signal: npt.NDArray[np.float64],
    labels: list[LoadLabel],
) -> tuple[dict[str, list[float]], float | None]:
    medians: dict[str, list[float]] = {"-1": [], "1": []}
    for direction in (-1, 1):
        directional = sorted(
            (label for label in labels if label.direction == direction),
            key=lambda label: label.level,
        )
        for label in directional:
            mask = (
                (samples[:, 0] >= label.start_ms)
                & (samples[:, 0] <= label.end_ms)
                & (samples[:, 3] == direction)
            )
            if np.any(mask):
                medians[str(direction)].append(safe_round(float(np.median(signal[mask]))))
    separations = [
        abs(second - first)
        for values in medians.values()
        for first, second in pairwise(values)
    ]
    separation = safe_round(float(np.median(separations))) if separations else None
    return medians, separation


def write_bands(path: Path, rows: list[BandRow]) -> None:
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=BAND_FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def main(
    capture: Path,
    output_dir: Annotated[Path, typer.Option("--output-dir")],
    labels: Annotated[Path | None, typer.Option("--labels")] = None,
) -> None:
    samples = read_capture(capture)
    load_labels = read_labels(labels)
    timestamps = samples[:, 0]
    deltas = np.diff(timestamps)
    signal = samples[:, 1] - samples[:, 2]
    baseline_mask = (samples[:, 3] == 0) | (samples[:, 4] == 0)
    baseline = signal[baseline_mask] if np.any(baseline_mask) else signal
    baseline_median = float(np.median(baseline))
    baseline_noise = float(np.std(baseline, ddof=1)) if len(baseline) > 1 else 0.0
    median_delta = float(np.median(deltas)) if len(deltas) else 0.0
    monotonic = bool(np.all(deltas > 0) and np.all(samples[:, 8] == 1))
    levels = {
        str(direction): sorted({label.level for label in load_labels if label.direction == direction})
        for direction in (-1, 1)
    }
    sufficient = all(len(values) >= 3 for values in levels.values())
    flat = bool(np.ptp(signal) == 0)
    reason = "PASS"
    if not monotonic:
        reason = "non_monotonic_timestamps"
    elif not sufficient:
        reason = "insufficient_known_load_levels"
    elif flat:
        reason = "flat_signal"
    medians, separation = known_load_metrics(samples, signal, load_labels)
    bands = aggregate_bands(samples, signal, baseline_median, baseline_noise)
    output_dir.mkdir(parents=True, exist_ok=True)
    write_bands(output_dir / "direction_command_bands.csv", bands)
    report = {
        "schema": "ossm-signal-characterization-v1",
        "verdict": "PASS" if reason == "PASS" else "FAIL",
        "reason": reason,
        "resistance_capability_enabled": reason == "PASS",
        "sample_count": len(samples),
        "baseline": {
            "sample_count": len(baseline),
            "median_adc": safe_round(baseline_median),
            "noise_std_adc": safe_round(baseline_noise),
        },
        "sample_rate_hz": optional_ratio(1000.0, median_delta),
        "freshness": {
            "timestamps_monotonic": monotonic,
            "median_interval_ms": safe_round(median_delta),
            "p95_interval_ms": safe_round(float(np.percentile(deltas, 95))) if len(deltas) else None,
            "max_interval_ms": safe_round(float(np.max(deltas))) if len(deltas) else None,
            "stale_interval_count": int(np.sum(deltas > median_delta * 2.5)) if median_delta else 0,
        },
        "saturation_rate": safe_round(float(np.mean(samples[:, 7]))),
        "known_external_loads": {
            "required_levels_per_direction": 3,
            "verified_levels_by_direction": levels,
            "median_signal_adc_by_direction": medians,
            "median_adjacent_separation_adc": separation,
        },
        "direction_command_band_count": len(bands),
    }
    (output_dir / "characterization.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    typer.echo(json.dumps({"verdict": report["verdict"], "reason": reason}))
    if reason != "PASS":
        raise typer.Exit(2)


if __name__ == "__main__":
    typer.run(main)
