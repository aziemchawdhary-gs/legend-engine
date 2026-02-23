"""TeamCity REST API client."""

import logging
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
from urllib.parse import urlparse

import requests

logger = logging.getLogger(__name__)


class TeamCityError(Exception):
    """Raised for TeamCity API failures."""


@dataclass
class BuildResult:
    """Result of a triggered build."""
    build_id: int
    build_type_id: str
    web_url: str
    success: bool = True
    error: Optional[str] = None


class TeamCityClient:
    """Client for TeamCity REST API interactions."""

    def __init__(
        self,
        server_url: str,
        username: str,
        password: str,
        insecure: bool = False,
        timeout: int = 30,
    ):
        self.server_url = server_url.rstrip("/")
        self.timeout = timeout
        self.verify_ssl = not insecure

        parsed = urlparse(self.server_url)
        self.origin = f"{parsed.scheme}://{parsed.netloc}"

        self._csrf_token = None

        self.session = requests.Session()
        self.session.auth = (username, password)
        self.session.headers.update({
            "Origin": self.origin,
        })

    def _url(self, path: str) -> str:
        """Build full URL from a path."""
        return f"{self.server_url}{path}"

    def _fetch_csrf_token(self) -> str:
        """Fetch a CSRF token from TeamCity.

        GET /authenticationTest.html?csrf returns the token as plain text.
        The token is cached for the lifetime of this client instance.
        """
        if self._csrf_token:
            return self._csrf_token

        url = self._url("/authenticationTest.html?csrf")
        logger.debug("Fetching CSRF token from %s", url)
        try:
            resp = self.session.get(
                url, timeout=self.timeout, verify=self.verify_ssl
            )
            resp.raise_for_status()
        except requests.RequestException as exc:
            raise TeamCityError(
                f"Failed to fetch CSRF token: {exc}"
            ) from exc

        self._csrf_token = resp.text.strip()
        logger.debug("Got CSRF token")
        return self._csrf_token

    def _request(
        self,
        method: str,
        path: str,
        **kwargs: Any,
    ) -> requests.Response:
        """Make an HTTP request with error handling."""
        url = self._url(path)
        kwargs.setdefault("timeout", self.timeout)
        kwargs.setdefault("verify", self.verify_ssl)

        # Add CSRF token for modifying requests
        if method.upper() in ("POST", "PUT", "DELETE"):
            headers = kwargs.get("headers", {})
            if "X-TC-CSRF-Token" not in headers:
                headers["X-TC-CSRF-Token"] = self._fetch_csrf_token()
                kwargs["headers"] = headers

        logger.debug("%s %s", method.upper(), url)

        try:
            resp = self.session.request(method, url, **kwargs)
        except requests.ConnectionError as exc:
            raise TeamCityError(
                f"Connection failed to {self.server_url}: {exc}"
            ) from exc
        except requests.Timeout as exc:
            raise TeamCityError(
                f"Request timed out after {self.timeout}s: {url}"
            ) from exc
        except requests.exceptions.SSLError as exc:
            raise TeamCityError(
                f"SSL error connecting to {self.server_url}. "
                "Use --insecure to skip SSL verification: {exc}"
            ) from exc

        logger.debug("Response: %d %s", resp.status_code, resp.reason)

        if not resp.ok:
            raise TeamCityError(
                f"TeamCity API error: {resp.status_code} {resp.reason}\n"
                f"URL: {url}\n"
                f"Response: {resp.text[:500]}"
            )

        return resp

    def upload_patch(
        self,
        patch_content: str,
        description: str = "Personal build from tc-build CLI",
    ) -> int:
        """Upload a diff patch to TeamCity.

        Returns the change ID.
        """
        resp = self._request(
            "POST",
            f"/uploadDiffChanges.html?description={requests.utils.quote(description)}"
            "&commitType=0",
            data=patch_content.encode("utf-8"),
            headers={"Content-Type": "text/text"},
        )

        try:
            change_id = int(resp.text.strip())
        except ValueError:
            raise TeamCityError(
                f"Unexpected response from patch upload "
                f"(expected change ID): {resp.text[:200]}"
            )

        logger.debug("Uploaded patch, change ID: %d", change_id)
        return change_id

    def trigger_build(
        self,
        build_type_id: str,
        change_id: int,
        comment: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Trigger a personal build for a single build configuration.

        Returns the build response dict.
        """
        payload: Dict[str, Any] = {
            "personal": True,
            "buildType": {"id": build_type_id},
            "lastChanges": {
                "change": [{"id": change_id, "personal": True}],
            },
            "triggered": {
                "type": "idePlugin",
                "details": "Unified Diff Patch",
            },
        }

        if comment:
            payload["comment"] = {"text": comment}

        resp = self._request(
            "POST",
            "/app/rest/buildQueue",
            json=payload,
            headers={
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )

        return resp.json()

    def trigger_builds(
        self,
        build_type_ids: List[str],
        change_id: int,
        comment: Optional[str] = None,
    ) -> List[BuildResult]:
        """Trigger personal builds for multiple build configurations.

        Continues on per-build failure. Returns list of BuildResult.
        """
        results: List[BuildResult] = []

        for i, bt_id in enumerate(build_type_ids):
            if i > 0:
                time.sleep(0.2)  # 200ms delay between calls

            try:
                data = self.trigger_build(bt_id, change_id, comment)
                build_id = data.get("id", 0)
                web_url = data.get(
                    "webUrl",
                    f"{self.server_url}/viewLog.html?buildId={build_id}",
                )
                results.append(BuildResult(
                    build_id=build_id,
                    build_type_id=bt_id,
                    web_url=web_url,
                ))
            except TeamCityError as exc:
                results.append(BuildResult(
                    build_id=0,
                    build_type_id=bt_id,
                    web_url="",
                    success=False,
                    error=str(exc),
                ))
                print(f"ERROR: Failed to trigger {bt_id}: {exc}", file=__import__("sys").stderr)

        return results

    def list_build_types(
        self, project_id: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """List build configurations, optionally filtered by project.

        Paginates in batches of 100. Includes VCS root entries with checkout
        rules so callers can filter by changed files.
        """
        all_build_types: List[Dict[str, Any]] = []
        start = 0
        batch_size = 100

        fields = (
            "buildType("
            "id,name,projectName,projectId,"
            "vcs-root-entries(vcs-root-entry(id,checkout-rules))"
            ")"
        )

        while True:
            locator_parts = [f"count:{batch_size}", f"start:{start}"]
            if project_id:
                locator_parts.insert(0, f"affectedProject:{project_id}")

            locator = ",".join(locator_parts)
            resp = self._request(
                "GET",
                f"/app/rest/buildTypes?locator={locator}&fields={fields}",
                headers={"Accept": "application/json"},
            )

            data = resp.json()
            items = data.get("buildType", [])
            all_build_types.extend(items)

            if len(items) < batch_size:
                break
            start += batch_size

        return all_build_types

    def get_build(self, build_id: int) -> Dict[str, Any]:
        """Get build details by ID."""
        resp = self._request(
            "GET",
            f"/app/rest/builds/id:{build_id}",
            headers={"Accept": "application/json"},
        )
        return resp.json()
