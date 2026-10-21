# Weston + XWayland client smoke test

After `tools/probe-weston-headless.sh` succeeds, this test tries to launch one already-installed X11 client as a child of Weston. It checks for `xterm`, `xeyes`, `xclock`, or `xmessage`, in that order. It never installs packages.

Run:

```sh
bash tools/probe-weston-x11-client.sh
```

The script starts Weston with the headless backend, Pixman renderer and XWayland, then starts the first available X11 client. It prints the Wayland socket result, whether the conventional X11 socket `tmp/.X11-unix/X0` exists, and logs. The Weston process is stopped when the script exits. Logs are retained in the printed temporary directory for inspection.

If no client is installed, the script exits with code 3 and names the clients it checked. Install a client only if you choose to; the probe does not change the package set.

A present X11 socket and clean startup logs are useful evidence, but not proof that the client surface was composited correctly. Headless rendering still does not export frames to the Android app. The separate Android-authorized IPC problem remains open.
