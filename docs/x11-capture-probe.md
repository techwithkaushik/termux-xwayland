# Real X11 capture probe

Run this on the Termux side after starting an X11/XWayland session:

```sh
pkg install ffmpeg
chmod +x tools/capture-x11-frame.sh
tools/capture-x11-frame.sh "$HOME/x11-check.png" 320x180
```

The script captures one frame from `$DISPLAY` to a local PNG. It checks that
`DISPLAY` and FFmpeg are available and rejects malformed dimensions. Choose a
capture size that fits inside the active X display.

**Important:** this is a diagnostic prerequisite only. It confirms that FFmpeg
can read real X11 pixels; it does not send those pixels to the Android app or
replace the bridge's synthetic test frame. Real-time frame streaming and
compositor integration remain unfinished. No TCP or localhost fallback is
used.
