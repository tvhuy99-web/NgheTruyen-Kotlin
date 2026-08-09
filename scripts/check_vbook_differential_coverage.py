#!/usr/bin/env python3
"""Check that reference differential captures cover every implemented feature used by the corpus.

This gate intentionally distinguishes three states:
- PARTIAL/PACKAGE_LAYER_PENDING: implementation blocker, captures cannot make it green.
- IMPLEMENTED but uncovered: proof blocker, more reference cases are required.
- IMPLEMENTED and covered: eligible for semantic comparison/certification.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys

REFERENCE_REJECTS = "REFERENCE_REJECTS"
IMPLEMENTED = "IMPLEMENTED"
PARTIAL = "PARTIAL"
PACKAGE_PENDING = "PACKAGE_LAYER_PENDING"


def load_json(path: pathlib.Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise RuntimeError(f"JSON_OBJECT_REQUIRED:{path}")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=pathlib.Path, help="VBookCorpusAuditMain JSON report")
    parser.add_argument("captures", type=pathlib.Path, nargs="+", help="capture_vbook_reference.py outputs")
    parser.add_argument("--allow-uncovered", action="append", default=[], help="Temporary feature waiver; repeated")
    args = parser.parse_args()

    try:
        corpus = load_json(args.corpus)
        if corpus.get("schema") != 2 or not isinstance(corpus.get("features"), list):
            raise RuntimeError("CORPUS_SCHEMA_2_REQUIRED")

        feature_rows: dict[str, dict] = {}
        for raw in corpus["features"]:
            if not isinstance(raw, dict):
                raise RuntimeError("CORPUS_FEATURE_INVALID")
            feature = str(raw.get("id") or "").strip()
            count = int(raw.get("count") or 0)
            implementation = str(raw.get("implementation") or "").strip()
            if feature and count > 0:
                feature_rows[feature] = raw
                if implementation not in {IMPLEMENTED, PARTIAL, PACKAGE_PENDING, REFERENCE_REJECTS}:
                    raise RuntimeError(f"CORPUS_IMPLEMENTATION_STATE_INVALID:{feature}:{implementation}")

        covered: dict[str, set[str]] = {}
        case_ids: set[str] = set()
        reference_errors: list[str] = []
        for capture_path in args.captures:
            capture = load_json(capture_path)
            if capture.get("schema") != 1 or not isinstance(capture.get("cases"), list):
                raise RuntimeError(f"CAPTURE_SCHEMA_1_REQUIRED:{capture_path}")
            for raw_case in capture["cases"]:
                if not isinstance(raw_case, dict):
                    raise RuntimeError(f"CAPTURE_CASE_INVALID:{capture_path}")
                case_id = str(raw_case.get("id") or "").strip()
                if not case_id or case_id in case_ids:
                    raise RuntimeError(f"CAPTURE_CASE_ID_DUPLICATE:{case_id}")
                case_ids.add(case_id)
                response = raw_case.get("response")
                if not isinstance(response, dict) or response.get("code") != 200:
                    reference_errors.append(case_id)
                    continue
                for feature in raw_case.get("features") or []:
                    feature = str(feature).strip()
                    if feature:
                        covered.setdefault(feature, set()).add(case_id)

        waived = {str(value).strip() for value in args.allow_uncovered if str(value).strip()}
        partial_required = sorted(
            feature for feature, row in feature_rows.items()
            if row["implementation"] in {PARTIAL, PACKAGE_PENDING}
        )
        uncovered = sorted(
            feature for feature, row in feature_rows.items()
            if row["implementation"] == IMPLEMENTED and feature not in covered and feature not in waived
        )
        unknown_capture_features = sorted(set(covered) - set(feature_rows))

        result = {
            "requiredFeatureCount": len(feature_rows),
            "referenceCaseCount": len(case_ids),
            "coveredImplementedFeatures": sorted(
                feature for feature in covered
                if feature in feature_rows and feature_rows[feature]["implementation"] == IMPLEMENTED
            ),
            "uncoveredImplementedFeatures": uncovered,
            "partialOrPackageBlockingFeatures": partial_required,
            "waivedFeatures": sorted(waived),
            "unknownCaptureFeatures": unknown_capture_features,
            "referenceErrorCases": sorted(reference_errors),
        }
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))

        if reference_errors:
            return 4
        if partial_required:
            return 3
        if uncovered:
            return 2
        return 0
    except Exception as exc:
        print(f"VBOOK_DIFFERENTIAL_COVERAGE_FAILED:{exc}", file=sys.stderr)
        return 5


if __name__ == "__main__":
    raise SystemExit(main())
