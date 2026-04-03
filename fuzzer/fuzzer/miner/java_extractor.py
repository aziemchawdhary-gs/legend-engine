"""Extract Pure DSL code from Java test files (string concatenations)."""
import os
import re
from typing import List

STRING_LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def _unescape_java_string(s: str) -> str:
    s = s.replace("\\n", "\n")
    s = s.replace("\\t", "\t")
    s = s.replace("\\\\", "\\")
    s = s.replace('\\"', '"')
    s = s.replace("\\r", "\r")
    return s


def _reassemble_concat(text: str) -> str:
    parts = STRING_LITERAL_RE.findall(text)
    return _unescape_java_string("".join(parts))


def extract_pure_strings_from_java(java_code: str) -> List[str]:
    results = []
    concat_pattern = re.compile(
        r'("(?:[^"\\]|\\.)*"'
        r'(?:\s*\+\s*\n?\s*'
        r'"(?:[^"\\]|\\.)*")*)',
        re.MULTILINE
    )

    for match in concat_pattern.finditer(java_code):
        assembled = _reassemble_concat(match.group(0))
        if "###" in assembled:
            results.append(assembled)

    return results


def extract_from_java_directory(directory: str, pattern: str = "Test*.java") -> List[str]:
    import fnmatch
    all_strings = []
    for root, _dirs, files in os.walk(directory):
        for filename in sorted(files):
            if fnmatch.fnmatch(filename, pattern):
                filepath = os.path.join(root, filename)
                with open(filepath, "r", encoding="utf-8", errors="replace") as f:
                    java_code = f.read()
                strings = extract_pure_strings_from_java(java_code)
                all_strings.extend(strings)
    return all_strings
