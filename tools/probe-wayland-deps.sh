#!/data/data/com.termux/files/usr/bin/bash
# Read-only Termux feasibility probe for a real Wayland compositor/XWayland stack.
# Does not install packages, start servers, change permissions, or print secrets.
set -u

section() { printf '\n== %s ==\n' "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

section "Runtime"
printf 'uname -m: '; uname -m
printf 'dpkg architecture: '; dpkg --print-architecture 2>/dev/null || printf 'unavailable\n'
printf 'PREFIX: %s\n' "${PREFIX:-unset}"
printf 'TERMUX_VERSION: %s\n' "${TERMUX_VERSION:-unknown}"

section "Toolchain"
for tool in pkg-config cc clang rustc cargo cmake meson ninja wayland-scanner Xwayland Xwayland.wrap weston wayland-info; do
  if have "$tool"; then printf 'FOUND  %s: %s\n' "$tool" "$(command -v "$tool")"; else printf 'MISS   %s\n' "$tool"; fi
done

section "pkg-config modules"
if have pkg-config; then
  for module in wayland-server wayland-client wayland-protocols xkbcommon pixman-1 egl glesv2 libdrm x11 xcb xcb-xfixes xcb-composite xcb-shape xcb-render xcb-randr xcb-xinput; do
    if pkg-config --exists "$module"; then
      printf 'FOUND  %-22s %s\n' "$module" "$(pkg-config --modversion "$module" 2>/dev/null || printf 'version-unknown')"
    else
      printf 'MISS   %s\n' "$module"
    fi
  done
else
  printf 'pkg-config unavailable; module checks skipped\n'
fi

section "Relevant installed packages"
if have dpkg-query; then
  dpkg-query -W -f='${binary:Package}\t${Version}\n' 2>/dev/null |
    grep -Ei '(^|:)(wayland|xwayland|xorg-server|xorgproto|libxkbcommon|pixman|mesa|libdrm|xcb|libx11|libxext|libxrender|libxfixes|libxrandr|libxcomposite|libxdamage|libxfont|fontconfig|freetype|smithay)' |
    sort || true
else
  printf 'dpkg-query unavailable\n'
fi

section "Interpretation"
printf '%s\n'   'This is a read-only inventory, not proof that a compositor can build or run.'   'Missing modules may be available as Termux packages or may require porting/build work.'   'Do not infer XWayland support from Termux:X11 alone; XWayland needs a Wayland compositor integration.'   'No package was installed and no service/socket was started.'
