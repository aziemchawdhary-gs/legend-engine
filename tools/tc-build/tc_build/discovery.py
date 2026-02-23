"""Build configuration discovery and caching."""

import json
import logging
import time
from dataclasses import dataclass, field, asdict
from fnmatch import fnmatch
from pathlib import Path
from typing import List, Optional, Tuple

logger = logging.getLogger(__name__)

CACHE_DIR = Path.home() / ".tc-build" / "cache"
CACHE_TTL = 3600  # 1 hour


@dataclass
class BuildType:
    """Represents a TeamCity build configuration."""
    id: str
    name: str
    project_name: str
    project_id: str
    checkout_rules: List[str] = field(default_factory=list)


def fetch_build_types(client, project_id: Optional[str] = None) -> List[BuildType]:
    """Fetch build configurations from TeamCity, with caching.

    Uses the client's list_build_types() method and converts to BuildType objects.
    """
    # Check cache first
    cached = _load_cache(project_id)
    if cached is not None:
        logger.debug("Using cached build types (%d entries)", len(cached))
        return cached

    raw = client.list_build_types(project_id)
    build_types = []
    for bt in raw:
        checkout_rules: List[str] = []
        vcs_entries = bt.get("vcs-root-entries", {})
        for entry in vcs_entries.get("vcs-root-entry", []):
            rules = entry.get("checkout-rules", "")
            if rules:
                checkout_rules.append(rules)
        build_types.append(BuildType(
            id=bt.get("id", ""),
            name=bt.get("name", ""),
            project_name=bt.get("projectName", ""),
            project_id=bt.get("projectId", ""),
            checkout_rules=checkout_rules,
        ))

    _save_cache(project_id, build_types)
    return build_types


def filter_build_types(build_types: List[BuildType], pattern: str) -> List[BuildType]:
    """Filter build types by name using substring or glob matching."""
    pattern_lower = pattern.lower()
    results = []
    for bt in build_types:
        name_lower = bt.name.lower()
        # Try substring match first, then glob
        if pattern_lower in name_lower or fnmatch(name_lower, pattern_lower):
            results.append(bt)
    return results


def _cache_key(project_id: Optional[str]) -> str:
    """Generate cache filename for a project."""
    return f"build_types_{project_id or 'all'}.json"


def _load_cache(project_id: Optional[str]) -> Optional[List[BuildType]]:
    """Load cached build types if still valid."""
    cache_file = CACHE_DIR / _cache_key(project_id)
    if not cache_file.exists():
        return None

    try:
        data = json.loads(cache_file.read_text())
    except (json.JSONDecodeError, OSError):
        return None

    if time.time() - data.get("timestamp", 0) > CACHE_TTL:
        logger.debug("Cache expired for project=%s", project_id)
        return None

    return [BuildType(**bt) for bt in data.get("build_types", [])]


def _save_cache(project_id: Optional[str], build_types: List[BuildType]) -> None:
    """Save build types to cache."""
    try:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        data = {
            "timestamp": time.time(),
            "build_types": [asdict(bt) for bt in build_types],
        }
        cache_file = CACHE_DIR / _cache_key(project_id)
        cache_file.write_text(json.dumps(data))
    except OSError as exc:
        logger.debug("Failed to write cache: %s", exc)


def _parse_checkout_rules(rules_str: str) -> List[Tuple[bool, str]]:
    """Parse a checkout-rules string into (include, vcs_path) tuples.

    Rules format: newline-separated, each line is [+|-:]VCSPath[=>AgentPath].
    Default (no operator) is +:.
    """
    parsed: List[Tuple[bool, str]] = []
    for line in rules_str.splitlines():
        line = line.strip()
        if not line:
            continue
        if line.startswith("-:"):
            include = False
            path = line[2:]
        elif line.startswith("+:"):
            include = True
            path = line[2:]
        else:
            include = True
            path = line

        # Strip agent-side mapping (=>...)
        if "=>" in path:
            path = path.split("=>", 1)[0]

        path = path.strip().rstrip("/")
        parsed.append((include, path))
    return parsed


def match_checkout_rules(rules_str: str, changed_files: List[str]) -> bool:
    """Check if any changed file matches the checkout rules.

    Returns True if at least one changed file is included by the rules.
    """
    rules = _parse_checkout_rules(rules_str)
    if not rules:
        return True  # no rules = matches everything

    for filepath in changed_files:
        # Find the most specific (longest prefix) matching rule
        best_match: Optional[Tuple[bool, str]] = None
        best_len = -1
        for include, vcs_path in rules:
            if not vcs_path:
                # Empty path matches everything
                if 0 > best_len:
                    best_match = (include, vcs_path)
                    best_len = 0
                continue
            # Check if the file is under this VCS path prefix
            if filepath == vcs_path or filepath.startswith(vcs_path + "/"):
                if len(vcs_path) > best_len:
                    best_match = (include, vcs_path)
                    best_len = len(vcs_path)

        if best_match is not None and best_match[0]:
            return True

    return False


def filter_by_changed_files(
    build_types: List[BuildType], changed_files: List[str]
) -> List[BuildType]:
    """Filter build types to those whose checkout rules match the changed files.

    Build types with no checkout rules match everything (included by default).
    """
    if not changed_files:
        return list(build_types)

    result: List[BuildType] = []
    for bt in build_types:
        if not bt.checkout_rules:
            # No rules = matches all files
            result.append(bt)
            continue
        # Check if any VCS root entry's rules match
        for rules_str in bt.checkout_rules:
            if match_checkout_rules(rules_str, changed_files):
                result.append(bt)
                break
    return result
