#!/usr/bin/env bash
set -Eeuo pipefail

# Fetch a version-pinned standalone upstream XWayland release archive.
# This is intentionally separate from the legacy xserver git submodule: that
# pinned revision does not contain the Meson/XWayland backend.
readonly VERSION="24.1.14"
readonly BASE_URL="https://xorg.freedesktop.org/releases/individual/xserver"
DEST_DIR="${1:-build/third_party}"
readonly ARCHIVE="xwayland-${VERSION}.tar.xz"
readonly SOURCE_DIR="${DEST_DIR}/xwayland-${VERSION}"

for tool in curl tar sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "ERROR: required tool not found: $tool" >&2
        exit 127
    }
done

mkdir -p "$DEST_DIR"
tmp_archive="${DEST_DIR}/.${ARCHIVE}.download"
trap 'rm -f "$tmp_archive"' EXIT

echo "Downloading official XWayland release: ${VERSION}"
curl --fail --location --silent --show-error \
    --proto '=https' --tlsv1.2 \
    "${BASE_URL}/${ARCHIVE}" --output "$tmp_archive"

# Refuse absolute paths or parent traversal before extracting the archive.
if tar -tf "$tmp_archive" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    echo "ERROR: archive contains an unsafe path" >&2
    exit 1
fi

rm -rf "$SOURCE_DIR"
tar -xf "$tmp_archive" -C "$DEST_DIR"
rm -f "$tmp_archive"
trap - EXIT

if [[ ! -f "$SOURCE_DIR/meson.build" || ! -f "$SOURCE_DIR/hw/xwayland/meson.build" ]]; then
    echo "ERROR: XWayland ${VERSION} archive lacks required Meson files" >&2
    exit 2
fi

if ! grep -Eq "version: ['\"]${VERSION}['\"]" "$SOURCE_DIR/meson.build"; then
    echo "ERROR: extracted source version does not match pinned version ${VERSION}" >&2
    exit 2
fi

printf 'Verified source layout: %s\n' "$SOURCE_DIR"
printf 'Pinned release archive SHA-256: '
sha256sum "$DEST_DIR/$ARCHIVE" 2>/dev/null || true
echo "NOTE: HTTPS and source-layout/version checks are used here; cryptographic release-signature verification is not yet configured."
