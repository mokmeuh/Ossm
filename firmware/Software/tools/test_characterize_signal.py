# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy>=2.3", "pytest>=8.4", "typer>=0.16"]
# ///
# --- How to run ---
# uv run --with numpy --with pytest --with typer pytest firmware/Software/tools/test_characterize_signal.py

from __future__ import annotations

import csv
import json
import subprocess
import sys
from pathlib import Path
from typing import Final

import pytest


TOOL: Final = Path(__file__).with_name("characterize_signal.py")
CAPTURE_FIELDS: Final = (
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


def write_fixture(root: Path, scenario: str) -> tuple[Path, Path]:
    capture = root / "capture.csv"
    labels = root / "labels.csv"
    rows: list[dict[str, int]] = []
    timestamp = 0
    for direction in (-1, 1):
        for load_level in (0, 1, 2, 3):
            for repeat in range(4):
                signal = 0 if scenario == "flat" else load_level * 40 + repeat
                rows.append(
                    {
                        "timestamp_ms": timestamp,
                        "raw_adc": 2000 + direction * signal,
                        "offset_adc": 2000,
                        "direction": direction if load_level else 0,
                        "command_speed_millihz": abs(direction) * load_level * 1000,
                        "command_acceleration": 100,
                        "command_position": direction * load_level * 10,
                        "saturated": 0,
                        "timestamp_monotonic": 1,
                    },
                )
                timestamp += 10
    if scenario == "shuffled":
        rows[12], rows[13] = rows[13], rows[12]
    with capture.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=CAPTURE_FIELDS)
        writer.writeheader()
        writer.writerows(rows)

    levels = (1, 2) if scenario == "insufficient" else (1, 2, 3)
    with labels.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=(
                "start_ms",
                "end_ms",
                "direction",
                "load_level",
                "verified_external_load",
            ),
        )
        writer.writeheader()
        for direction_index, direction in enumerate((-1, 1)):
            base = direction_index * 160
            for level in levels:
                writer.writerow(
                    {
                        "start_ms": base + level * 40,
                        "end_ms": base + level * 40 + 39,
                        "direction": direction,
                        "load_level": level,
                        "verified_external_load": "true",
                    },
                )
    return capture, labels


@pytest.mark.parametrize(
    ("scenario", "expected_reason"),
    [
        ("flat", "flat_signal"),
        ("shuffled", "non_monotonic_timestamps"),
        ("insufficient", "insufficient_known_load_levels"),
    ],
)
def test_rejects_unusable_characterization_inputs(
    tmp_path: Path,
    scenario: str,
    expected_reason: str,
) -> None:
    capture, labels = write_fixture(tmp_path, scenario)
    output = tmp_path / "output"

    completed = subprocess.run(
        [
            sys.executable,
            str(TOOL),
            str(capture),
            "--labels",
            str(labels),
            "--output-dir",
            str(output),
        ],
        check=False,
        capture_output=True,
        text=True,
    )

    assert completed.returncode == 2
    report = json.loads((output / "characterization.json").read_text(encoding="utf-8"))
    assert report["verdict"] == "FAIL"
    assert report["reason"] == expected_reason
    assert report["resistance_capability_enabled"] is False
