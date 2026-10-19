# Optional X11 capture diagnostic

This helper is for debugging only. It can confirm that FFmpeg can read an existing X11 display, but it is not used by the app or the live rendering path.

```sh
pkg install ffmpeg
tools/capture-x11-frame.sh "$HOME/x11-check.png" 320x180
```

The script reads `$DISPLAY` and writes one PNG. It checks for an active display, FFmpeg availability and a valid positive `WIDTHxHEIGHT` argument.

**It does not stream frames to Android.** The intended live path exports the compositor's own rendered output buffer through the authenticated bridge. Do not add FFmpeg as a runtime dependency merely to implement screen rendering.
