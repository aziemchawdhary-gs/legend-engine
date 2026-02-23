"""Configuration management with layered sources: defaults < file < env < CLI."""

import logging
import os
import stat
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

try:
    import tomllib
except ModuleNotFoundError:
    import tomli as tomllib

logger = logging.getLogger(__name__)

CONFIG_DIR = Path.home() / ".tc-build"
CONFIG_FILE = CONFIG_DIR / "config.toml"
REPO_CONFIG_FILE = ".tc-build.toml"

# Environment variable mapping
ENV_MAP = {
    "server_url": "TC_SERVER_URL",
    "username": "TC_USERNAME",
    "password": "TC_PASSWORD",
    "default_project": "TC_PROJECT",
}


class ConfigError(Exception):
    """Raised for configuration validation failures."""


@dataclass
class Config:
    server_url: str = ""
    username: str = ""
    password: str = ""
    default_project: str = ""
    base_branch: str = "origin/master"
    poll_interval: int = 30
    favorites: List[str] = field(default_factory=list)
    insecure: bool = False
    verbose: bool = False


def _find_config_file() -> Optional[Path]:
    """Find config file: ~/.tc-build/config.toml or .tc-build.toml in repo root."""
    if CONFIG_FILE.exists():
        return CONFIG_FILE
    repo_config = Path.cwd() / REPO_CONFIG_FILE
    if repo_config.exists():
        return repo_config
    return None


def _load_file_config(path: Optional[Path] = None) -> Dict[str, Any]:
    """Load configuration from TOML file."""
    config_path = path or _find_config_file()
    if config_path is None:
        return {}

    logger.debug("Loading config from %s", config_path)

    # Check permissions on user config file
    if config_path == CONFIG_FILE and config_path.exists():
        mode = config_path.stat().st_mode
        if mode & (stat.S_IRGRP | stat.S_IWGRP | stat.S_IROTH | stat.S_IWOTH):
            print(
                f"WARNING: Config file {config_path} has overly permissive "
                f"permissions ({oct(mode & 0o777)}). Run: chmod 600 {config_path}",
                file=sys.stderr,
            )

    try:
        with open(config_path, "rb") as f:
            return tomllib.load(f)
    except (OSError, tomllib.TOMLDecodeError) as exc:
        raise ConfigError(f"Failed to read config file {config_path}: {exc}") from exc


def _load_env_config() -> Dict[str, Any]:
    """Load configuration from environment variables."""
    result: Dict[str, Any] = {}
    for config_key, env_var in ENV_MAP.items():
        value = os.environ.get(env_var)
        if value:
            result[config_key] = value
    return result


def _merge_cli_args(cli_args: Any) -> Dict[str, Any]:
    """Extract config-relevant fields from CLI args namespace."""
    result: Dict[str, Any] = {}
    mapping = {
        "server": "server_url",
        "username": "username",
        "password": "password",
        "project": "default_project",
        "base_branch": "base_branch",
        "poll_interval": "poll_interval",
        "insecure": "insecure",
        "verbose": "verbose",
    }
    for arg_name, config_key in mapping.items():
        value = getattr(cli_args, arg_name, None)
        if value is not None:
            # For booleans, only override if explicitly True
            if isinstance(value, bool) and not value:
                continue
            result[config_key] = value
    return result


def load_config(cli_args: Any = None) -> Config:
    """Load config by merging: defaults <- file <- env <- CLI flags."""
    config = Config()

    # Layer 1: File config
    file_config = _load_file_config()
    for key, value in file_config.items():
        if hasattr(config, key):
            setattr(config, key, value)

    # Layer 2: Environment variables
    env_config = _load_env_config()
    for key, value in env_config.items():
        if hasattr(config, key):
            setattr(config, key, value)

    # Layer 3: CLI args
    if cli_args is not None:
        cli_config = _merge_cli_args(cli_args)
        for key, value in cli_config.items():
            if hasattr(config, key):
                setattr(config, key, value)

    return config


def validate_config(config: Config) -> None:
    """Validate required config fields. Raises ConfigError on failure."""
    missing = []
    if not config.server_url:
        missing.append("server_url (--server or TC_SERVER_URL)")
    if not config.username:
        missing.append("username (--username or TC_USERNAME)")
    if not config.password:
        missing.append("password (--password or TC_PASSWORD)")

    if missing:
        raise ConfigError(
            "Missing required configuration:\n  - " + "\n  - ".join(missing)
            + "\n\nRun 'tc-build init' to create a config file, "
            "or set environment variables."
        )

    if not config.insecure and not config.server_url.startswith("https://"):
        raise ConfigError(
            f"Server URL '{config.server_url}' does not use HTTPS. "
            "Use --insecure to allow plain HTTP connections."
        )


def create_config_file(
    server_url: str,
    username: str,
    default_project: str = "",
    base_branch: str = "origin/master",
) -> Path:
    """Create config file at ~/.tc-build/config.toml. Never writes password."""
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)

    lines = [
        '# tc-build configuration',
        '# Password is NEVER stored here. Use TC_PASSWORD env var or --password flag.',
        '',
        f'server_url = "{server_url}"',
        f'username = "{username}"',
    ]
    if default_project:
        lines.append(f'default_project = "{default_project}"')
    lines.append(f'base_branch = "{base_branch}"')
    lines.append("")

    CONFIG_FILE.write_text("\n".join(lines) + "\n")

    # Set permissions to 600 (owner read/write only)
    CONFIG_FILE.chmod(0o600)

    return CONFIG_FILE
