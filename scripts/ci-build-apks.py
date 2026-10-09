#!/usr/bin/env python3
"""Build Pocket and publish to the Sutliff Apps store.

Single-module app: gradle :app:assembleRelease, then publish.
"""
import glob
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_MANIFEST = os.path.join(ROOT, "app-manifest.yaml")


def find_apk() -> str | None:
    base = os.path.join(ROOT, "app", "build", "outputs", "apk")
    cands = [
        p for p in glob.glob(os.path.join(base, "**", "*.apk"), recursive=True)
        if "release" in p and "unsigned" not in os.path.basename(p)
    ]
    return cands[0] if cands else None


def apk_version(apk: str) -> str:
    sdk = os.environ.get("ANDROID_HOME", "")
    aapt = None
    found = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt2")))
    if found:
        aapt = found[-1]
    if not aapt or not os.path.exists(aapt):
        return "unknown"
    try:
        out = subprocess.run(
            [aapt, "dump", "badging", apk],
            capture_output=True, text=True, timeout=120, check=True,
        ).stdout
        m = re.search(r"versionName='([^']*)'", out)
        return m.group(1) if m else "unknown"
    except Exception as e:
        print(f"warn: version readback failed: {e}")
        return "unknown"


def apk_version_code(apk: str) -> int | None:
    sdk = os.environ.get("ANDROID_HOME", "")
    for cand in sorted(glob.glob(f"{sdk}/build-tools/*/aapt2"), reverse=True):
        if not os.path.exists(cand):
            continue
        try:
            out = subprocess.run([cand, "dump", "badging", apk],
                                 capture_output=True, text=True, timeout=120, check=True).stdout
        except Exception:
            continue
        m = re.search(r"versionCode='(\d+)'", out)
        if m:
            return int(m.group(1))
    return None


def main() -> int:
    # Load app-manifest.yaml
    import yaml
    with open(APP_MANIFEST) as f:
        manifest = yaml.safe_load(f)
    app_cfg = manifest["apk"]
    pkg = app_cfg["package"]
    store_dir = app_cfg["storeDir"]
    task = app.get("task", "assembleRelease")

    print(f"=== build Pocket (app:{task}) ===", flush=True)
    cmd = ["./gradlew", f":app:{task}", "--no-daemon"]
    r = subprocess.run(cmd, cwd=ROOT)
    if r.returncode != 0:
        print(f"!! build FAILED (exit {r.returncode})")
        return r.returncode

    apk = find_apk()
    if not apk:
        print("!! gradle succeeded but no signed release APK found")
        return 1
    print(f"   built {apk}")

    # Publish
    version = apk_version(apk)
    version_code = apk_version_code(apk)
    meta = {
        "app": pkg,
        "name": app_cfg.get("name", "Pocket"),
        "version": version,
        "apk": f"{store_dir}/{os.path.basename(apk)}",
        "size": os.path.getsize(apk),
        "built": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "versionCode": version_code,
    }
    print(f"=== publish {pkg} v{version} -> {store_dir} ===", flush=True)
    env = dict(os.environ, PUBLISH_MANIFEST=json.dumps(meta))
    r = subprocess.run(
        [sys.executable, os.path.join(ROOT, "scripts", "ci-publish-apk.py"), apk],
        env=env,
    )
    if r.returncode != 0:
        return r.returncode
    return 0


if __name__ == "__main__":
    sys.exit(main())
