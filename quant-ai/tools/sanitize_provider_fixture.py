from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


QUANT_AI_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(QUANT_AI_ROOT))

from app.agent_team.offline_fixture import sanitize_fixture  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Sanitize one local provider JSON sample without network access.",
    )
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--allow",
        required=True,
        help="Comma-separated top-level field whitelist.",
    )
    args = parser.parse_args()
    input_path = args.input.resolve()
    output_path = args.output.resolve()
    if input_path == output_path:
        raise ValueError("raw evidence and committable fixture paths must differ")
    raw = json.loads(input_path.read_text(encoding="utf-8"))
    result = sanitize_fixture(
        raw,
        (item.strip() for item in args.allow.split(",") if item.strip()),
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(
        json.dumps(
            {
                "fixture": result.value,
                "canonical": result.canonical,
                "sha256": result.sha256,
            },
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
