"""CLI to mine Pure DSL corpus from legend-engine source tree."""
import argparse
import json
import os
import sys

from .pure_extractor import extract_sections_from_directory, extract_sections
from .java_extractor import extract_from_java_directory


def main():
    parser = argparse.ArgumentParser(description="Mine Pure DSL corpus from legend-engine")
    parser.add_argument("engine_root", help="Path to legend-engine root directory")
    parser.add_argument("--output", "-o", default="corpus/raw", help="Output directory for extracted sections")
    args = parser.parse_args()

    os.makedirs(args.output, exist_ok=True)

    all_sections = []

    print("Extracting from .pure files...")
    pure_sections = extract_sections_from_directory(args.engine_root)
    print(f"  Found {len(pure_sections)} sections from .pure files")
    all_sections.extend(pure_sections)

    print("Extracting from Java test files...")
    java_strings = extract_from_java_directory(args.engine_root, pattern="Test*.java")
    for i, s in enumerate(java_strings):
        sections = extract_sections(s, source=f"java-string-{i}")
        all_sections.extend(sections)
    print(f"  Found {len(java_strings)} Java strings, {len(all_sections) - len(pure_sections)} additional sections")

    by_type = {}
    for section in all_sections:
        t = section["type"]
        by_type.setdefault(t, []).append(section)

    for section_type, sections in sorted(by_type.items()):
        outfile = os.path.join(args.output, f"{section_type.lower()}_sections.jsonl")
        with open(outfile, "w") as f:
            for s in sections:
                f.write(json.dumps(s) + "\n")
        print(f"  Wrote {len(sections)} {section_type} sections to {outfile}")

    print(f"\nTotal: {len(all_sections)} sections across {len(by_type)} types")


if __name__ == "__main__":
    main()
