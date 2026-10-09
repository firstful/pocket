#!/usr/bin/env python3
"""ci-publish-apk.py <apk_path> — publish a built APK to the Sutliff Apps store.

Reads PUBLISH_MANIFEST env var (set by ci-build-apks.py), POSTs the APK and
a version manifest to the store backend. Auth via APPSTORE_PUBLISH_TOKEN.
"""
import json
import os
import subprocess
import sys

STORE = os.environ.get("APPSTORE_URL", "http://apps.ffilt.us")


def post(path, files=None, data=None):
    cmd = ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-X", "POST",
           "-H", f"X-Publish-Token: {os.environ['APPSTORE_PUBLISH_TOKEN']}"]
    if files:
        cmd += ["-F", f"payload=@{files}"]
    if data:
        cmd += ["-F", f"manifest={json.dumps(data)}"]
    cmd.append(STORE + path)
    r = subprocess.run(cmd, capture_output=True, text=True)
    return r.stdout.strip()


def main():
    apk = sys.argv[1]
    if os.environ.get("PUBLISH_MANIFEST"):
        manifest = json.loads(os.environ["PUBLISH_MANIFEST"])
    else:
        print("PUBLISH_MANIFEST not set")
        sys.exit(1)

    store_dir = manifest["apk"].split("/")[0]
    code = post(f"/publish/{store_dir}/{os.path.basename(apk)}", files=apk, data=manifest)
    print(f"publish -> {STORE}/publish/{store_dir}/{os.path.basename(apk)} (http {code})")
    if not code.startswith("2"):
        sys.exit(f"publish failed: http {code}")


if __name__ == "__main__":
    main()
