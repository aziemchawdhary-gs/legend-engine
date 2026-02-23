"""Build configuration discovery and caching."""

import json
import logging
import time
from dataclasses import dataclass, asdict
from fnmatch import fnmatch
from pathlib import Path
from typing import List, Optional

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
    build_types = [
        BuildType(
            id=bt.get("id", ""),
            name=bt.get("name", ""),
            project_name=bt.get("projectName", ""),
            project_id=bt.get("projectId", ""),
        )
        for bt in raw
    ]

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
