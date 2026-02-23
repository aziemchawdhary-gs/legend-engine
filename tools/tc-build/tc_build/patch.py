"""Git diff patch generation and validation."""

import logging
import subprocess
import sys
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

MAX_PATCH_SIZE = 50 * 1024 * 1024  # 50 MB


class PatchError(Exception):
    """Raised for patch generation or validation failures."""


def _run_git(*args: str) -> subprocess.CompletedProcess:
    """Run a git command and return the result."""
    cmd = ["git"] + list(args)
    logger.debug("Running: %s", " ".join(cmd))
    try:
        return subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=False,
        )
    except FileNotFoundError:
        raise PatchError("git is not installed or not in PATH.") from None


def _check_git_repo() -> None:
    """Verify current directory is inside a git repository."""
    result = _run_git("rev-parse", "--is-inside-work-tree")
    if result.returncode != 0:
        raise PatchError(
            "Not a git repository. Run this command from within a git working tree."
        )


def _check_branch_reachable(base_branch: str) -> None:
    """Verify the base branch ref is reachable."""
    result = _run_git("rev-parse", "--verify", base_branch)
    if result.returncode != 0:
        raise PatchError(
            f"Base branch '{base_branch}' is not reachable. "
            f"Try running: git fetch origin"
        )


def generate_patch(base_branch: str = "origin/master") -> str:
    """Generate a unified diff patch from base_branch...HEAD.

    Returns the patch content as a string.
    """
    _check_git_repo()
    _check_branch_reachable(base_branch)

    result = _run_git("diff", f"{base_branch}...HEAD")
    if result.returncode != 0:
        raise PatchError(f"git diff failed: {result.stderr.strip()}")

    patch = result.stdout
    if not patch.strip():
        print(
            "WARNING: Empty diff — no changes between HEAD and "
            f"'{base_branch}'. Nothing to submit.",
            file=sys.stderr,
        )

    return patch


def load_patch_file(path: str) -> str:
    """Read a pre-generated patch file."""
    patch_path = Path(path)
    if not patch_path.exists():
        raise PatchError(f"Patch file not found: {path}")

    try:
        content = patch_path.read_text()
    except OSError as exc:
        raise PatchError(f"Failed to read patch file {path}: {exc}") from exc

    if not content.strip():
        raise PatchError(f"Patch file is empty: {path}")

    return content


def validate_patch(content: str) -> None:
    """Validate patch content. Warns on issues but does not raise."""
    size = len(content.encode("utf-8"))
    if size > MAX_PATCH_SIZE:
        print(
            f"WARNING: Patch is very large ({size / (1024*1024):.1f} MB). "
            "TeamCity may reject patches over 50 MB. Consider splitting your changes.",
            file=sys.stderr,
        )

    if not (content.startswith("diff ") or content.startswith("---")):
        print(
            "WARNING: Patch does not appear to start with a unified diff header. "
            "TeamCity may not accept this format.",
            file=sys.stderr,
        )


def get_patch(patch_file: Optional[str], base_branch: str) -> str:
    """High-level entry point: load from file or generate from git.

    Returns the patch content.
    """
    if patch_file:
        logger.debug("Loading patch from file: %s", patch_file)
        content = load_patch_file(patch_file)
    else:
        logger.debug("Generating patch against %s", base_branch)
        content = generate_patch(base_branch)

    validate_patch(content)
    return content
