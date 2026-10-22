#!/usr/bin/env bash
set -Eeuo pipefail

# Fetch a version-pinned standalone upstream XWayland release archive.
# The legacy xserver gitlink in this repository does not contain the required
# Meson/XWayland backend at its current pinned revision.
readonly VERSION="24.1.14"
readonly BASE_URL="https://xorg.freedesktop.org/releases/individual/xserver"
# SHA-256 pinned by the official Termux XWayland package recipe for 24.1.14.
readonly EXPECTED_SHA256="4eb6b98d678299a4b96138d886345b294130695760345ef84596225a8224078a"
DEST_DIR="${1:-build/third_party}"
readonly ARCHIVE="xwayland-${VERSION}.tar.xz"
readonly ARCHIVE_PATH="${DEST_DIR}/${ARCHIVE}"
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

# Verify against the checksum pinned by the official Termux package recipe.
actual_sha256="$(sha256sum "$tmp_archive" | awk '{print $1}')"
if [[ "$actual_sha256" != "$EXPECTED_SHA256" ]]; then
    echo "ERROR: XWayland archive SHA-256 mismatch" >&2
    echo "Expected: $EXPECTED_SHA256" >&2
    echo "Actual:   $actual_sha256" >&2
    exit 1
fi

# Refuse absolute paths or parent traversal before extracting the archive.
if tar -tf "$tmp_archive" | grep -Eq '(^/|(^|/)\.\.(/|$))'; then
    echo "ERROR: archive contains an unsafe path" >&2
    exit 1
fi

mv "$tmp_archive" "$ARCHIVE_PATH"
trap - EXIT
rm -rf "$SOURCE_DIR"
tar -xf "$ARCHIVE_PATH" -C "$DEST_DIR"

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
sha256sum "$ARCHIVE_PATH"
echo "Verified SHA-256 against the official Termux XWayland 24.1.14 package recipe."
echo "NOTE: detached OpenPGP release-signature verification is not yet configured."
