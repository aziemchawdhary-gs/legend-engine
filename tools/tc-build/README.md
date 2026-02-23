# tc-build — TeamCity Personal Build CLI

Trigger TeamCity personal builds from the command line, no IntelliJ required.

## Quick Start

```bash
# One-time setup
cd tools/tc-build
export TC_PASSWORD='your-teamcity-password'
./tc-build init

# Trigger a personal build
./tc-build trigger --build-type MyProject_Build

# Dry run (no API calls)
./tc-build trigger --build-type MyProject_Build --dry-run
```

## Installation

### Option 1: Direct execution (no install)

```bash
# Install dependencies
pip install -r tools/tc-build/requirements.txt

# Run directly
./tools/tc-build/tc-build trigger --build-type bt123
```

### Option 2: pip install

```bash
cd tools/tc-build
pip install -e .

# Now available globally
tc-build trigger --build-type bt123
```

## Configuration

Configuration is loaded in priority order: CLI flags > env vars > config file > defaults.

### Config File

Created by `tc-build init` at `~/.tc-build/config.toml`:

```toml
server_url = "https://teamcity.example.com"
username = "your-username"
default_project = "LegendEngine"
base_branch = "origin/master"
```

Password is **never** stored in the config file.

### Environment Variables

| Variable | Description |
|----------|-------------|
| `TC_SERVER_URL` | TeamCity server URL |
| `TC_USERNAME` | Username for authentication |
| `TC_PASSWORD` | Password (recommended method) |
| `TC_PROJECT` | Default project filter |

### CLI Flags

All config values can be overridden via CLI flags. Run `tc-build --help` for full details.

## Usage

### Trigger a build with known config ID

```bash
tc-build trigger --build-type MyProject_UnitTests
```

### Trigger multiple builds

```bash
tc-build trigger --build-type MyProject_UnitTests,MyProject_IntegrationTests
```

### Use a pre-generated patch

```bash
git diff origin/master...HEAD > my.patch
tc-build trigger --patch-file my.patch --build-type MyProject_Build
```

### Diff against a different branch

```bash
tc-build trigger --base-branch origin/develop --build-type MyProject_Build
```

### List build configurations

```bash
tc-build list --project LegendEngine
tc-build list --filter "Unit Test"
```

### Interactive selection

```bash
# Omit --build-type to get an interactive picker
tc-build trigger --project LegendEngine
```

### Monitor builds

```bash
tc-build trigger --build-type MyProject_Build --wait
tc-build status --build-id 12345
```

### Dry run

```bash
tc-build trigger --build-type MyProject_Build --dry-run
```

## Commands

| Command | Description |
|---------|-------------|
| `trigger` | Generate patch, trigger builds (default) |
| `list` | List available build configurations |
| `init` | Create configuration file interactively |
| `status` | Check status of a build |

## Troubleshooting

### "Not a git repository"
Run `tc-build` from within a git working tree.

### "Base branch not reachable"
Run `git fetch origin` to update remote refs.

### "SSL error"
If using a self-signed certificate, pass `--insecure`. For production use, install the CA certificate.

### Empty diff warning
Your branch has no changes relative to the base branch. Check that you're on the right branch and have committed your changes.

### Authentication failures
Verify your username and password. TeamCity may require the "Change build source code with a custom patch" permission for personal builds.

## Security

- Passwords are never written to disk or logged
- Config file is created with `chmod 600` permissions
- HTTPS is enforced unless `--insecure` is explicitly passed
- `--verbose` mode never prints credentials
