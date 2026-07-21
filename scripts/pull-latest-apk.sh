#!/bin/bash
# Pulls the latest successful Android debug APK from GitHub Actions and
# publishes it to the public downloads folder on this server.
# Requires GITHUB_TOKEN_FILE to contain a GitHub token scoped to
# read-only Actions access on acplrwa/acplrwa-mobile.
set -euo pipefail

REPO="acplrwa/acplrwa-mobile"
WORKFLOW="android.yml"
ARTIFACT_NAME="acplrwa-mis-debug-apk"
TOKEN_FILE="/etc/acplrwa-mobile/github_token"
DEST="/var/www/html/downloads/acplrwa-mis-debug.apk"
STATE_FILE="/var/lib/acplrwa-mobile/last-run-id"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

if [ ! -f "$TOKEN_FILE" ]; then
    echo "ERROR: token file $TOKEN_FILE not found" >&2
    exit 1
fi
TOKEN="$(cat "$TOKEN_FILE")"
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json")

RUN_JSON="$(curl -sf "${AUTH[@]}" \
    "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/runs?branch=main&status=success&per_page=1")"
RUN_ID="$(echo "$RUN_JSON" | python3 -c "import json,sys; d=json.load(sys.stdin); r=d.get('workflow_runs',[]); print(r[0]['id'] if r else '')")"

if [ -z "$RUN_ID" ]; then
    echo "No successful Android Build run found yet."
    exit 0
fi

mkdir -p "$(dirname "$STATE_FILE")"
if [ -f "$STATE_FILE" ] && [ "$(cat "$STATE_FILE")" = "$RUN_ID" ]; then
    echo "Run $RUN_ID already published, nothing to do."
    exit 0
fi

ARTIFACTS_JSON="$(curl -sf "${AUTH[@]}" \
    "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts")"
DL_URL="$(echo "$ARTIFACTS_JSON" | python3 -c "
import json,sys
d=json.load(sys.stdin)
for a in d.get('artifacts', []):
    if a['name'] == '$ARTIFACT_NAME':
        print(a['archive_download_url']); break
")"

if [ -z "$DL_URL" ]; then
    echo "ERROR: artifact $ARTIFACT_NAME not found on run $RUN_ID" >&2
    exit 1
fi

curl -sfL "${AUTH[@]}" "$DL_URL" -o "$TMPDIR/artifact.zip"
unzip -q "$TMPDIR/artifact.zip" -d "$TMPDIR/extracted"

APK_PATH="$(find "$TMPDIR/extracted" -name '*.apk' | head -1)"
if [ -z "$APK_PATH" ]; then
    echo "ERROR: no .apk found inside artifact" >&2
    exit 1
fi

install -m 644 -o www-data -g www-data "$APK_PATH" "$DEST"
echo "$RUN_ID" > "$STATE_FILE"
echo "Published run $RUN_ID -> $DEST"
