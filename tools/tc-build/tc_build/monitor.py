"""Build progress monitoring."""

import logging
import signal
import sys
import time
from dataclasses import dataclass
from typing import Dict, List, Optional

logger = logging.getLogger(__name__)


@dataclass
class BuildStatus:
    """Status of a single build."""
    build_id: int
    state: str          # "queued", "running", "finished"
    status: str         # "SUCCESS", "FAILURE", "ERROR", "UNKNOWN"
    percentage: int     # 0-100, only meaningful when running
    status_text: str    # human-readable status
    web_url: str


class BuildMonitor:
    """Monitors build progress by polling TeamCity."""

    def __init__(self, client, poll_interval: int = 30):
        self.client = client
        self.poll_interval = poll_interval
        self._stop = False

    def poll_build(self, build_id: int) -> BuildStatus:
        """Poll a single build's status."""
        data = self.client.get_build(build_id)
        state = data.get("state", "unknown").lower()
        status = data.get("status", "UNKNOWN")
        web_url = data.get("webUrl", "")

        percentage = 0
        status_text = state
        running_info = data.get("running-info")
        if running_info:
            percentage = running_info.get("percentageComplete", 0)
            status_text = running_info.get("currentStageText", state)

        if state == "finished":
            status_text = f"Finished: {status}"

        return BuildStatus(
            build_id=build_id,
            state=state,
            status=status,
            percentage=percentage,
            status_text=status_text,
            web_url=web_url,
        )

    def monitor(self, build_ids: List[int]) -> int:
        """Monitor multiple builds until completion.

        Returns exit code: 0 if all succeed, 1 if any fail.
        """
        if not build_ids:
            return 0

        # Handle Ctrl+C gracefully
        original_handler = signal.getsignal(signal.SIGINT)

        def _handle_sigint(signum, frame):
            self._stop = True
            print(
                "\nMonitoring stopped. Builds continue running on TeamCity.",
                file=sys.stderr,
            )

        signal.signal(signal.SIGINT, _handle_sigint)

        is_tty = sys.stdout.isatty()
        statuses: Dict[int, BuildStatus] = {}
        finished: set = set()

        try:
            while not self._stop and len(finished) < len(build_ids):
                for bid in build_ids:
                    if bid in finished:
                        continue
                    try:
                        s = self.poll_build(bid)
                        statuses[bid] = s
                        if s.state == "finished":
                            finished.add(bid)
                    except Exception as exc:
                        logger.debug("Error polling build %d: %s", bid, exc)

                self._render(build_ids, statuses, is_tty)

                if len(finished) < len(build_ids) and not self._stop:
                    time.sleep(self.poll_interval)
        finally:
            signal.signal(signal.SIGINT, original_handler)

        # Final render
        self._render(build_ids, statuses, is_tty, final=True)

        # Determine exit code
        all_success = all(
            statuses.get(bid, BuildStatus(bid, "", "UNKNOWN", 0, "", "")).status
            == "SUCCESS"
            for bid in build_ids
        )
        return 0 if all_success else 1

    def _render(
        self,
        build_ids: List[int],
        statuses: Dict[int, BuildStatus],
        is_tty: bool,
        final: bool = False,
    ) -> None:
        """Render build status to terminal."""
        lines = []
        for bid in build_ids:
            s = statuses.get(bid)
            if s is None:
                lines.append(f"  Build #{bid}: polling...")
                continue

            if s.state == "running":
                bar = _progress_bar(s.percentage)
                lines.append(f"  Build #{bid}: {bar} {s.percentage}% — {s.status_text}")
            elif s.state == "finished":
                marker = "OK" if s.status == "SUCCESS" else "FAIL"
                lines.append(f"  Build #{bid}: [{marker}] {s.status_text}  {s.web_url}")
            else:
                lines.append(f"  Build #{bid}: {s.status_text}")

        output = "\n".join(lines)

        if is_tty and not final:
            # Move cursor up to overwrite previous output
            num_lines = len(build_ids)
            sys.stdout.write(f"\033[{num_lines}A\033[J")

        print(output)
        sys.stdout.flush()


def _progress_bar(percent: int, width: int = 20) -> str:
    """Render a simple ASCII progress bar."""
    filled = int(width * percent / 100)
    return "[" + "#" * filled + "-" * (width - filled) + "]"
