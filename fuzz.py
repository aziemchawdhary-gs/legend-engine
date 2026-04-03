#!/usr/bin/env python3
"""Main CLI entrypoint for the Legend Engine DSL fuzzer."""
import argparse
import os
import sys
import uuid

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "fuzzer"))

from fuzzer.strategies.grammar_walk import GrammarWalkStrategy
from fuzzer.harness_client import HarnessClient
from fuzzer.reporter import FuzzReporter


def find_harness_jar():
    candidates = [
        os.path.join(os.path.dirname(__file__), "harness", "target", "legend-engine-fuzz-harness-fuzz-SNAPSHOT.jar"),
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return None


def run_grammar_walk(args):
    jar_path = args.jar or find_harness_jar()
    if not jar_path:
        print("ERROR: Could not find harness JAR. Build it first or pass --jar.", file=sys.stderr)
        sys.exit(1)

    strategy = GrammarWalkStrategy(seed=args.seed)
    reporter = FuzzReporter(args.results_dir)

    section_types = ["pure", "relational", "full_m2m", "full_relational"]
    mutation_types = ["delete_token", "swap_tokens", "duplicate_token", "inject_garbage"]

    print(f"Starting grammar-walk fuzzing with harness: {jar_path}")
    print(f"Generating {args.count} inputs...")

    with HarnessClient(jar_path, timeout_ms=args.timeout) as harness:
        for i in range(args.count):
            input_id = f"gw-{uuid.uuid4().hex[:8]}"

            if i % 3 == 0:
                section_type = section_types[i % len(section_types)]
                code = strategy.generate(section_type=section_type)
            else:
                section_type = section_types[i % len(section_types)]
                code = strategy.generate(section_type=section_type)
                mutation = mutation_types[i % len(mutation_types)]
                code = strategy.mutate(code, mutation_type=mutation)

            try:
                result = harness.classify(input_id, code)
                reporter.record(input_id, code, result)

                if result.get("result", "").endswith("_CRASH"):
                    print(f"  CRASH found: {input_id} — {result.get('exception', 'unknown')}")

                if (i + 1) % 100 == 0:
                    print(f"  Progress: {i + 1}/{args.count}")
            except Exception as e:
                print(f"  Error processing {input_id}: {e}", file=sys.stderr)

    reporter.print_summary()
    report_file = reporter.write_report()
    print(f"Report saved to: {report_file}")


def run_report(args):
    crashes_dir = os.path.join(args.results_dir, "crashes")
    candidates_dir = os.path.join(args.results_dir, "test_candidates")

    crash_count = len(os.listdir(crashes_dir)) if os.path.isdir(crashes_dir) else 0
    candidate_count = len(os.listdir(candidates_dir)) if os.path.isdir(candidates_dir) else 0

    print(f"Results in {args.results_dir}:")
    print(f"  Crashes:         {crash_count}")
    print(f"  Test candidates: {candidate_count}")


def main():
    parser = argparse.ArgumentParser(description="Legend Engine DSL Fuzzer")
    parser.add_argument("--results-dir", default="results", help="Directory for results")
    parser.add_argument("--jar", help="Path to harness JAR")
    parser.add_argument("--timeout", type=int, default=10000, help="Timeout per input in ms")
    parser.add_argument("--seed", type=int, default=None, help="Random seed")

    subparsers = parser.add_subparsers(dest="command")

    gw = subparsers.add_parser("grammar-walk", help="Run grammar-walk fuzzing strategy")
    gw.add_argument("--count", type=int, default=1000, help="Number of inputs to generate")

    subparsers.add_parser("report", help="Show results report")

    args = parser.parse_args()

    if args.command == "grammar-walk":
        run_grammar_walk(args)
    elif args.command == "report":
        run_report(args)
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
