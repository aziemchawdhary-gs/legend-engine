"""Collects and summarizes fuzzing results."""
import json
import os
from collections import Counter
from typing import Dict, List


class FuzzReporter:
    """Collects fuzzing results and generates summary reports."""

    def __init__(self, results_dir: str):
        self._results_dir = results_dir
        self._crashes_dir = os.path.join(results_dir, "crashes")
        self._candidates_dir = os.path.join(results_dir, "test_candidates")
        self._reports_dir = os.path.join(results_dir, "reports")
        os.makedirs(self._crashes_dir, exist_ok=True)
        os.makedirs(self._candidates_dir, exist_ok=True)
        os.makedirs(self._reports_dir, exist_ok=True)

        self._stats = Counter()
        self._results: List[Dict] = []

    def record(self, input_id: str, code: str, result: Dict):
        self._stats[result.get("result", "UNKNOWN")] += 1
        self._results.append({"id": input_id, "result": result})

        classification = result.get("result", "")

        if classification.endswith("_CRASH"):
            crash_file = os.path.join(self._crashes_dir, f"{input_id}.json")
            with open(crash_file, "w") as f:
                json.dump({"id": input_id, "code": code, **result}, f, indent=2)

        if classification == "COMPILE_OK":
            candidate_file = os.path.join(self._candidates_dir, f"{input_id}.pure")
            with open(candidate_file, "w") as f:
                f.write(code)

    def summary(self) -> Dict:
        return {
            "total": sum(self._stats.values()),
            "breakdown": dict(self._stats),
            "crashes": self._stats.get("PARSE_CRASH", 0) + self._stats.get("COMPILE_CRASH", 0),
            "test_candidates": self._stats.get("COMPILE_OK", 0),
        }

    def write_report(self):
        report = self.summary()
        report_file = os.path.join(self._reports_dir, "summary.json")
        with open(report_file, "w") as f:
            json.dump(report, f, indent=2)
        return report_file

    def print_summary(self):
        s = self.summary()
        print(f"\n{'='*50}")
        print(f"Fuzzing Summary")
        print(f"{'='*50}")
        print(f"Total inputs:     {s['total']}")
        print(f"Crashes found:    {s['crashes']}")
        print(f"Test candidates:  {s['test_candidates']}")
        print(f"\nBreakdown:")
        for result_type, count in sorted(s["breakdown"].items()):
            print(f"  {result_type}: {count}")
        print(f"{'='*50}\n")
