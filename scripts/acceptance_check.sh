#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ]; then
  echo "Usage: $0 /path/to/sessions"
  exit 2
fi

SESSIONS_ROOT="$1"

python3 - <<'PY' "$SESSIONS_ROOT"
import json
import os
import sys

root = sys.argv[1]
if not os.path.isdir(root):
    print(f"ERROR: sessions root not found: {root}")
    sys.exit(1)

required_quality = {
    "session_id",
    "timestamp",
    "score_0_100",
    "pass",
    "metrics",
    "top_reason",
}
required_metrics = {
    "blur_score",
    "illumination_mean",
    "coverage_ratio",
    "stability_variance",
}
required_match = {"session_id", "probe_filename", "threshold_used", "candidates"}
required_candidate = {"candidate_id", "score", "decision"}
required_liveness = {"session_id", "decision", "score", "heuristic_used"}

errors = 0
session_count = 0

for tenant in sorted(os.listdir(root)):
    tenant_dir = os.path.join(root, tenant)
    if not os.path.isdir(tenant_dir):
        continue
    for session in sorted(os.listdir(tenant_dir)):
        session_dir = os.path.join(tenant_dir, session)
        if not os.path.isdir(session_dir):
            continue
        session_count += 1
        raw_path = os.path.join(session_dir, "raw.png")
        roi_path = os.path.join(session_dir, "roi.png")
        quality_path = os.path.join(session_dir, "quality.json")
        for path in (raw_path, roi_path, quality_path):
            if not os.path.exists(path):
                errors += 1
                print(f"Missing {os.path.basename(path)} in {session_dir}")

        if os.path.exists(quality_path):
            try:
                with open(quality_path, "r", encoding="utf-8") as f:
                    quality = json.load(f)
                missing = required_quality - set(quality.keys())
                if missing:
                    errors += 1
                    print(f"quality.json missing keys {sorted(missing)} in {session_dir}")
                metrics = quality.get("metrics", {})
                if not isinstance(metrics, dict):
                    errors += 1
                    print(f"quality.json metrics not object in {session_dir}")
                else:
                    missing_metrics = required_metrics - set(metrics.keys())
                    if missing_metrics:
                        errors += 1
                        print(f"quality.json metrics missing {sorted(missing_metrics)} in {session_dir}")
            except Exception as exc:
                errors += 1
                print(f"quality.json parse error in {session_dir}: {exc}")

        match_path = os.path.join(session_dir, "match.json")
        if os.path.exists(match_path):
            try:
                with open(match_path, "r", encoding="utf-8") as f:
                    match = json.load(f)
                missing = required_match - set(match.keys())
                if missing:
                    errors += 1
                    print(f"match.json missing keys {sorted(missing)} in {session_dir}")
                candidates = match.get("candidates", [])
                if not isinstance(candidates, list):
                    errors += 1
                    print(f"match.json candidates not list in {session_dir}")
                else:
                    for cand in candidates:
                        if not isinstance(cand, dict):
                            errors += 1
                            print(f"match.json candidate not object in {session_dir}")
                            continue
                        missing_cand = required_candidate - set(cand.keys())
                        if missing_cand:
                            errors += 1
                            print(f"match.json candidate missing {sorted(missing_cand)} in {session_dir}")
            except Exception as exc:
                errors += 1
                print(f"match.json parse error in {session_dir}: {exc}")

        liveness_path = os.path.join(session_dir, "liveness.json")
        if os.path.exists(liveness_path):
            try:
                with open(liveness_path, "r", encoding="utf-8") as f:
                    liveness = json.load(f)
                missing = required_liveness - set(liveness.keys())
                if missing:
                    errors += 1
                    print(f"liveness.json missing keys {sorted(missing)} in {session_dir}")
            except Exception as exc:
                errors += 1
                print(f"liveness.json parse error in {session_dir}: {exc}")

if errors:
    print(f"FAILED: {errors} issue(s) found across {session_count} session(s)")
    sys.exit(1)

print(f"OK: {session_count} session(s) checked")
PY
