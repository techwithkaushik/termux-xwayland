# ADR 0001: Cross-app IPC without a TCP frame fallback

- Status: Direct abstract AF_UNIX socket migration implemented; target-device verification pending
- Date: 2026-10-20

## Context and constraints

The Android display host and ordinary Termux run under different Android UIDs. The original Android-to-Termux abstract AF_UNIX prototype failed with EACCES on the target Android 10 device. The later Binder FD path then failed during Termux app_process startup/bindService on the user's MIUI device. The user explicitly requires direct IPC, no Binder dependency from Termux, and no separate companion APK.

The implementation must not require root, SELinux policy changes, allow-external-apps=true, Termux RUN_COMMAND, or a TCP/localhost listener for frames or input. The loopback status monitor is control-plane only.

## Decision

Retire the Binder FD service and use the existing Android host APK as a session-specific abstract AF_UNIX server. Termux launches a Java client through app_process under the existing Termux UID. A fresh 128-bit session ID names and authenticates the socket; the Android host checks the kernel-reported peer UID against the installed Termux package UID. The client is compiled to DEX in CI and included in the Termux DEB. No second APK is built or installed.

TXWB v1 framing retains the 12-byte header and 16 MiB payload ceiling. The client sends PING and validates PONG, and forwards bounded RGBA XWayland frames when ffmpeg capture is available. INPUT/RESIZE/CLIPBOARD routing and native Wayland capture remain incomplete.

## Verification boundary

CI compilation and package tests verify build and CLI wiring only. Because cross-UID AF_UNIX access previously returned EACCES on the target device, the reverse-direction connection must be verified on Android 10 / MIUI. Do not claim success until the phone confirms a socket connection and increasing frame count.
