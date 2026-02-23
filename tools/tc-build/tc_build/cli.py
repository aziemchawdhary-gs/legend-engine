"""CLI entry point with argparse subcommand dispatch."""

import argparse
import getpass
import logging
import sys
from typing import List, Optional

from . import __version__
from .api import BuildResult, TeamCityClient, TeamCityError
from .config import Config, ConfigError, create_config_file, load_config, validate_config
from .discovery import BuildType, fetch_build_types, filter_build_types, filter_by_changed_files
from .monitor import BuildMonitor
from .patch import PatchError, extract_changed_files, get_patch

logger = logging.getLogger("tc_build")


def build_parser() -> argparse.ArgumentParser:
    """Build the argument parser with subcommands."""
    parser = argparse.ArgumentParser(
        prog="tc-build",
        description="TeamCity Personal Build CLI — trigger personal builds without IntelliJ.",
    )
    parser.add_argument(
        "--version", action="version", version=f"%(prog)s {__version__}"
    )

    # Global flags
    parser.add_argument("--server", help="TeamCity server URL")
    parser.add_argument("--username", help="TeamCity username")
    parser.add_argument("--password", help="TeamCity password (prefer TC_PASSWORD env var)")
    parser.add_argument("--project", help="Filter build configs by project ID")
    parser.add_argument("-v", "--verbose", action="store_true", help="Enable debug logging")
    parser.add_argument("--insecure", action="store_true", help="Allow plain HTTP connections")

    subparsers = parser.add_subparsers(dest="command")

    # trigger (default command)
    trigger = subparsers.add_parser("trigger", help="Generate patch and trigger personal builds")
    trigger.add_argument("--patch-file", help="Use a pre-generated patch file")
    trigger.add_argument("--base-branch", help="Base branch for diff (default: origin/master)")
    trigger.add_argument(
        "--build-type",
        help="Build configuration ID(s), comma-separated. Required in Phase 1.",
    )
    trigger.add_argument("--description", help="Patch description for TeamCity")
    trigger.add_argument("--comment", help="Build comment")
    trigger.add_argument("--wait", action="store_true", help="Monitor builds until completion")
    trigger.add_argument(
        "--poll-interval", type=int, help="Polling interval in seconds (default: 30)"
    )
    trigger.add_argument(
        "--dry-run", action="store_true", help="Show what would be triggered without doing it"
    )
    trigger.add_argument("--filter", help="Filter build config names by pattern")
    trigger.add_argument(
        "--favorites", action="store_true", help="Show only favorited build configs"
    )

    # list
    list_cmd = subparsers.add_parser("list", help="List available build configurations")
    list_cmd.add_argument("--filter", help="Filter build config names by pattern")

    # init
    subparsers.add_parser("init", help="Create or update configuration file")

    # status
    status_cmd = subparsers.add_parser("status", help="Check build status")
    status_cmd.add_argument(
        "--build-id", help="Build ID(s) to check, comma-separated"
    )

    return parser


def prompt_password(config: Config) -> str:
    """Get password from config, or prompt interactively."""
    if config.password:
        return config.password
    if sys.stdin.isatty():
        return getpass.getpass("TeamCity password: ")
    print("ERROR: No password provided. Set TC_PASSWORD or use --password.", file=sys.stderr)
    sys.exit(1)


def _parse_build_type_ids(raw: Optional[str]) -> List[str]:
    """Parse comma-separated build type IDs."""
    if not raw:
        return []
    return [bt.strip() for bt in raw.split(",") if bt.strip()]


def select_build_types_interactive(build_types: List[BuildType]) -> List[BuildType]:
    """Present a TUI menu and let the user select build configurations."""
    if not build_types:
        print("No build configurations found.", file=sys.stderr)
        return []

    from simple_term_menu import TerminalMenu

    entries = [f"[{bt.id}] {bt.project_name} :: {bt.name}" for bt in build_types]

    menu = TerminalMenu(
        entries,
        title=f"Select build configurations ({len(build_types)} available)  "
              "[/=search, space=toggle, enter=confirm]",
        multi_select=True,
        show_multi_select_hint=True,
        show_search_hint=True,
        multi_select_select_on_accept=False,
        multi_select_empty_ok=False,
    )

    menu.show()
    selected_indices = menu.chosen_menu_indices

    if selected_indices is None:
        print("\nSelection cancelled.", file=sys.stderr)
        return []

    return [build_types[i] for i in selected_indices]


def cmd_trigger(args: argparse.Namespace, config: Config) -> int:
    """Handle the 'trigger' subcommand."""
    # Resolve base branch
    base_branch = getattr(args, "base_branch", None) or config.base_branch

    # Generate/load patch
    try:
        patch_content = get_patch(getattr(args, "patch_file", None), base_branch)
    except PatchError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    patch_size = len(patch_content.encode("utf-8"))

    # Determine build type IDs
    raw_ids = getattr(args, "build_type", None)
    build_type_ids = _parse_build_type_ids(raw_ids)

    if not build_type_ids:
        # Phase 2: interactive selection
        password = prompt_password(config)
        config.password = password

        try:
            validate_config(config)
        except ConfigError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 1

        client = TeamCityClient(
            config.server_url, config.username, config.password,
            insecure=config.insecure,
        )

        try:
            build_types = fetch_build_types(client, config.default_project or None)
        except TeamCityError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 1

        # Auto-narrow by changed files
        changed_files = extract_changed_files(patch_content)
        if changed_files:
            total = len(build_types)
            build_types = filter_by_changed_files(build_types, changed_files)
            if build_types:
                print(
                    f"Filtered to {len(build_types)} build configs matching "
                    f"changed files (from {total} total)"
                )
            else:
                print(
                    "WARNING: No build configs match changed files. "
                    "Showing all configs.",
                    file=sys.stderr,
                )
                build_types = fetch_build_types(client, config.default_project or None)

        # Apply favorites filter
        if getattr(args, "favorites", False) and config.favorites:
            fav_set = set(config.favorites)
            build_types = [bt for bt in build_types if bt.id in fav_set]

        # Apply name filter
        name_filter = getattr(args, "filter", None)
        if name_filter:
            build_types = filter_build_types(build_types, name_filter)

        if not sys.stdin.isatty():
            print(
                "ERROR: No --build-type specified and stdin is not a terminal "
                "for interactive selection.",
                file=sys.stderr,
            )
            return 1

        selected = select_build_types_interactive(build_types)
        if not selected:
            print("No build configurations selected.", file=sys.stderr)
            return 1

        build_type_ids = [bt.id for bt in selected]

    # Confirmation for large selections
    if len(build_type_ids) > 3:
        if sys.stdin.isatty():
            answer = input(
                f"\nAbout to trigger {len(build_type_ids)} builds. Continue? [y/N] "
            ).strip().lower()
            if answer not in ("y", "yes"):
                print("Aborted.", file=sys.stderr)
                return 1

    # Dry run
    if getattr(args, "dry_run", False):
        print("=== DRY RUN ===")
        print(f"Patch size: {patch_size / 1024:.1f} KB")
        print(f"Base branch: {base_branch}")
        print(f"Build configurations ({len(build_type_ids)}):")
        for bt_id in build_type_ids:
            print(f"  - {bt_id}")
        print("No API calls made.")
        return 0

    # Ensure we have credentials
    password = prompt_password(config)
    config.password = password

    try:
        validate_config(config)
    except ConfigError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    client = TeamCityClient(
        config.server_url, config.username, config.password,
        insecure=config.insecure,
    )

    # Upload patch
    description = getattr(args, "description", None) or "Personal build from tc-build CLI"
    try:
        print(f"Uploading patch ({patch_size / 1024:.1f} KB)...")
        change_id = client.upload_patch(patch_content, description)
        print(f"Patch uploaded. Change ID: {change_id}")
    except TeamCityError as exc:
        print(f"ERROR: Failed to upload patch: {exc}", file=sys.stderr)
        return 1

    # Trigger builds
    comment = getattr(args, "comment", None)
    print(f"Triggering {len(build_type_ids)} build(s)...")
    results = client.trigger_builds(build_type_ids, change_id, comment)

    # Report results
    successes = [r for r in results if r.success]
    failures = [r for r in results if not r.success]

    if successes:
        print(f"\nTriggered {len(successes)} build(s):")
        for r in successes:
            print(f"  Build #{r.build_id} [{r.build_type_id}]: {r.web_url}")

    if failures:
        print(f"\nFailed to trigger {len(failures)} build(s):", file=sys.stderr)
        for r in failures:
            print(f"  [{r.build_type_id}]: {r.error}", file=sys.stderr)

    # Monitor if requested
    if getattr(args, "wait", False) and successes:
        print("\nMonitoring builds...")
        poll_interval = getattr(args, "poll_interval", None) or config.poll_interval
        monitor = BuildMonitor(client, poll_interval)
        build_ids = [r.build_id for r in successes]
        exit_code = monitor.monitor(build_ids)
        return exit_code

    if failures and not successes:
        return 1
    if failures:
        return 2  # partial failure
    return 0


def cmd_init(args: argparse.Namespace, config: Config) -> int:
    """Handle the 'init' subcommand — interactive config file creation."""
    print("tc-build configuration setup")
    print("=" * 40)
    print()

    try:
        server_url = input(f"TeamCity server URL [{config.server_url or 'https://teamcity.example.com'}]: ").strip()
        if not server_url:
            server_url = config.server_url or "https://teamcity.example.com"

        username = input(f"Username [{config.username or ''}]: ").strip()
        if not username:
            username = config.username

        default_project = input(f"Default project ID [{config.default_project or ''}]: ").strip()
        if not default_project:
            default_project = config.default_project

        base_branch = input(f"Base branch [{config.base_branch}]: ").strip()
        if not base_branch:
            base_branch = config.base_branch

    except (EOFError, KeyboardInterrupt):
        print("\nSetup cancelled.", file=sys.stderr)
        return 1

    path = create_config_file(server_url, username, default_project, base_branch)
    print(f"\nConfig written to: {path}")
    print("Permissions set to 600 (owner-only).")
    print()
    print("NOTE: Password is NOT stored in the config file.")
    print("Set it via: export TC_PASSWORD='your-password'")
    return 0


def cmd_list(args: argparse.Namespace, config: Config) -> int:
    """Handle the 'list' subcommand."""
    password = prompt_password(config)
    config.password = password

    try:
        validate_config(config)
    except ConfigError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    client = TeamCityClient(
        config.server_url, config.username, config.password,
        insecure=config.insecure,
    )

    project_id = config.default_project or None
    try:
        build_types = fetch_build_types(client, project_id)
    except TeamCityError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    name_filter = getattr(args, "filter", None)
    if name_filter:
        build_types = filter_build_types(build_types, name_filter)

    if not build_types:
        print("No build configurations found.")
        return 0

    print(f"Build configurations ({len(build_types)}):\n")
    for i, bt in enumerate(build_types, 1):
        print(f"  {i:3d}. [{bt.id}] {bt.project_name} :: {bt.name}")

    return 0


def cmd_status(args: argparse.Namespace, config: Config) -> int:
    """Handle the 'status' subcommand."""
    raw_ids = getattr(args, "build_id", None)
    if not raw_ids:
        print("ERROR: --build-id is required.", file=sys.stderr)
        return 1

    try:
        build_ids = [int(x.strip()) for x in raw_ids.split(",")]
    except ValueError:
        print("ERROR: --build-id must be comma-separated integers.", file=sys.stderr)
        return 1

    password = prompt_password(config)
    config.password = password

    try:
        validate_config(config)
    except ConfigError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    client = TeamCityClient(
        config.server_url, config.username, config.password,
        insecure=config.insecure,
    )

    monitor = BuildMonitor(client, config.poll_interval)
    for bid in build_ids:
        try:
            s = monitor.poll_build(bid)
            if s.state == "running":
                print(f"  Build #{bid}: {s.state} ({s.percentage}%) — {s.status_text}")
            elif s.state == "finished":
                print(f"  Build #{bid}: {s.status} — {s.status_text}  {s.web_url}")
            else:
                print(f"  Build #{bid}: {s.state} — {s.status_text}")
        except TeamCityError as exc:
            print(f"  Build #{bid}: ERROR — {exc}", file=sys.stderr)

    return 0


def main(argv: Optional[List[str]] = None) -> None:
    """Main entry point."""
    parser = build_parser()
    args = parser.parse_args(argv)

    # Set up logging
    if args.verbose:
        logging.basicConfig(
            level=logging.DEBUG,
            format="%(name)s %(levelname)s: %(message)s",
            stream=sys.stderr,
        )
    else:
        logging.basicConfig(
            level=logging.WARNING,
            format="%(message)s",
            stream=sys.stderr,
        )

    # Load config
    try:
        config = load_config(args)
    except ConfigError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)

    # Default command is 'trigger'
    command = args.command or "trigger"

    commands = {
        "trigger": cmd_trigger,
        "init": cmd_init,
        "list": cmd_list,
        "status": cmd_status,
    }

    handler = commands.get(command)
    if handler is None:
        parser.print_help()
        sys.exit(1)

    exit_code = handler(args, config)
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
