#!/usr/bin/env python3
"""Publish the checked-out SDK revision with a repository-scoped GH_TOKEN."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parents[1]
REPO = "TheBoldApps/notification.dev-SDKs"
ASSET = "NotificationCore.xcframework.zip"
API = f"https://api.github.com/repos/{REPO}"


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def manifest_release(manifest):
    match = re.search(
        rf'releases/download/(\d+\.\d+\.\d+)/{re.escape(ASSET)}"\s*,\s*'
        r'checksum:\s*"([a-f0-9]{64})"', manifest
    )
    if not match:
        raise RuntimeError("Package.swift must contain a release version and SHA-256 checksum")
    return match.groups()


def request(method, url, token, data=None, content_type="application/json", missing_ok=False):
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {token}",
        "X-GitHub-Api-Version": "2026-03-10",
        "Content-Type": content_type,
        "User-Agent": "notification-dev-release",
    }
    body = json.dumps(data).encode() if isinstance(data, dict) else data
    try:
        with urlopen(Request(url, data=body, headers=headers, method=method), timeout=120) as response:
            return json.load(response)
    except HTTPError as error:
        if missing_ok and error.code == 404:
            return None
        raise RuntimeError(
            f"GitHub returned HTTP {error.code} for {method} {url}. "
            "Check token expiry, selected repository, Contents write permission, "
            "organization approval, and repository tag rules."
        ) from None


def check_tag(token, version, sha):
    ref = request("GET", f"{API}/git/ref/tags/{version}", token, missing_ok=True)
    if ref is None:
        return
    obj = ref["object"]
    while obj["type"] == "tag":
        obj = request("GET", f"{API}/git/tags/{obj['sha']}", token)["object"]
    if obj["type"] != "commit" or obj["sha"] != sha:
        raise RuntimeError(f"Existing tag {version} does not point to HEAD; it will not be moved")


def publish(token, version, sha, archive, checksum):
    remote = request("GET", f"{API}/commits/{sha}", token)
    if remote["sha"] != sha:
        raise RuntimeError("HEAD is not available on GitHub; push the tested commit first")
    check_tag(token, version, sha)
    # List rather than get-by-tag so interrupted draft releases can be resumed.
    release = None
    page = 1
    while True:
        releases = request("GET", f"{API}/releases?per_page=100&page={page}", token)
        release = next((item for item in releases if item["tag_name"] == version), None)
        if release is not None or len(releases) < 100:
            break
        page += 1
    if release is not None:
        if not release["draft"]:
            raise RuntimeError(f"Release {version} is already published; nothing will be replaced")
        if release["target_commitish"] != sha:
            raise RuntimeError("Existing draft targets another revision; inspect it on GitHub")
    else:
        release = request("POST", f"{API}/releases", token, {
            "tag_name": version, "target_commitish": sha,
            "name": f"SDK {version}", "draft": True, "prerelease": False,
            "generate_release_notes": True,
        })
    release_id = release["id"]
    print(f"Draft ready: {release['html_url']}", flush=True)
    assets = request("GET", f"{API}/releases/{release_id}/assets?per_page=100", token)
    asset = next((item for item in assets if item["name"] == ASSET), None)
    if asset is None:
        upload_url = f"https://uploads.github.com/repos/{REPO}/releases/{release_id}/assets?"
        asset = request("POST", upload_url + urlencode({"name": ASSET}), token,
                        archive.read_bytes(), content_type="application/zip")
    if asset.get("state") != "uploaded" or asset.get("digest") != f"sha256:{checksum}":
        raise RuntimeError("Draft asset is incomplete or its digest differs; inspect the draft. Nothing was published")
    # Re-check immediately before publishing; never silently reuse a different tag.
    check_tag(token, version, sha)
    release = request("PATCH", f"{API}/releases/{release_id}", token, {"draft": False})
    print(f"Published: {release['html_url']}", flush=True)
    url = f"https://github.com/{REPO}/releases/download/{version}/{ASSET}"
    with urlopen(url, timeout=120) as response:
        downloaded = hashlib.sha256(response.read()).hexdigest()
    if downloaded != checksum:
        raise RuntimeError("Release is published, but public download checksum verification failed")
    print(f"Public archive verified: {checksum}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Exact ZIP whose checksum is committed in Package.swift")
    parser.add_argument("--publish", action="store_true", help="Upload and publish; default is a local preflight only")
    args = parser.parse_args()
    version, checksum = manifest_release((ROOT / "Package.swift").read_text())
    archive = args.archive or ROOT / "build" / ASSET
    actual = hashlib.sha256(archive.read_bytes()).hexdigest()
    if actual != checksum:
        raise RuntimeError("Archive checksum differs from Package.swift; do not rebuild or replace the committed release ZIP")
    sha = git("rev-parse", "HEAD")
    dirty = bool(git("status", "--porcelain"))
    print(f"Repository: {REPO}\nVersion: {version}\nCommit: {sha}\nArchive: {archive}\nSHA-256: {checksum}")
    if not args.publish:
        print(f"Local preflight passed. Working tree: {'dirty (commit before publishing)' if dirty else 'clean'}.")
        print("No network calls or changes made. Add --publish to release after verification.")
        return
    if dirty:
        raise RuntimeError("Working tree is dirty; commit and push the tested release sources first")
    token = os.environ.get("GH_TOKEN")
    if not token:
        raise RuntimeError("Set GH_TOKEN to a repository-scoped fine-grained token (Contents: write)")
    publish(token, version, sha, archive, checksum)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, URLError, subprocess.CalledProcessError) as error:
        print(f"Release stopped: {error}", file=sys.stderr)
        sys.exit(1)
