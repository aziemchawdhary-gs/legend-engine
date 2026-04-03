"""Client that communicates with the Java fuzz harness via subprocess stdin/stdout."""
import json
import subprocess
from typing import Dict, Optional


class HarnessClient:
    """Manages a subprocess running the Java FuzzHarness, communicating via JSON lines."""

    def __init__(self, jar_path: str, timeout_ms: int = 10000, java_cmd: str = "java"):
        self._jar_path = jar_path
        self._timeout_ms = timeout_ms
        self._java_cmd = java_cmd
        self._process: Optional[subprocess.Popen] = None

    def start(self):
        cmd = [
            self._java_cmd, "-jar", self._jar_path,
            "--timeout", str(self._timeout_ms),
        ]
        self._process = subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )

    def stop(self):
        if self._process:
            self._process.stdin.close()
            self._process.wait(timeout=5)
            self._process = None

    def classify(self, input_id: str, code: str) -> Dict:
        if not self._process or self._process.poll() is not None:
            raise RuntimeError("Harness process is not running")

        request = self._format_request(input_id, code)
        self._process.stdin.write(request + "\n")
        self._process.stdin.flush()

        response_line = self._process.stdout.readline()
        if not response_line:
            raise RuntimeError("Harness process closed stdout unexpectedly")

        return self._parse_response(response_line)

    def _format_request(self, input_id: str, code: str) -> str:
        return json.dumps({"id": input_id, "code": code})

    def _parse_response(self, line: str) -> Dict:
        return json.loads(line.strip())

    def _is_crash(self, result: Dict) -> bool:
        return result.get("result", "").endswith("_CRASH")

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *args):
        self.stop()
