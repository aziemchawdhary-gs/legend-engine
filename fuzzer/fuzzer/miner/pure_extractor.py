"""Extract Pure DSL sections from .pure files."""
import os
import re
from typing import List, Dict

SECTION_HEADER_RE = re.compile(r"^###(\w+)\s*$", re.MULTILINE)


def extract_sections(code: str, source: str = "<string>") -> List[Dict]:
    """Split Pure code into sections by ### headers.

    Returns a list of dicts: {"type": str, "code": str, "source": str}
    """
    matches = list(SECTION_HEADER_RE.finditer(code))

    if not matches:
        return [{"type": "Pure", "code": code.strip(), "source": source}]

    sections = []
    for i, match in enumerate(matches):
        section_type = match.group(1)
        start = match.end()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(code)
        section_code = code[start:end].strip()
        if section_code:
            sections.append({
                "type": section_type,
                "code": section_code,
                "source": source,
            })
    return sections


def extract_sections_from_directory(directory: str) -> List[Dict]:
    """Extract sections from all .pure files in a directory tree."""
    all_sections = []
    for root, _dirs, files in os.walk(directory):
        for filename in sorted(files):
            if filename.endswith(".pure"):
                filepath = os.path.join(root, filename)
                with open(filepath, "r", encoding="utf-8", errors="replace") as f:
                    code = f.read()
                sections = extract_sections(code, source=filepath)
                all_sections.extend(sections)
    return all_sections
