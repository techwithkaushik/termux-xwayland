# IPC capability probe: device test plan

## Purpose

Choose a supported Termux-to-Android IPC mechanism before implementing desktop streaming. This is a test plan, not a claim that a Binder bridge already exists.

## Constraints

- Preserve the user's existing Termux installation.
- No localhost TCP, Internet permission, SELinux changes, or root requirement.
- Android 10 / API 29 must be included.
- The Android app must authenticate the Termux-side endpoint and reject unauthorized clients.
- CI success is not evidence that Android SELinux or Binder authorization allows communication on a particular device.

## Candidate evaluation

### A. Termux:RUN_COMMAND

Use only as a control-plane probe to request a short-lived command when the installed Termux build supports the API and the user has enabled the required permission. Record whether the broadcast is accepted, whether the command executes, and how the result is returned.

Pass criteria:
- Explicitly documented package/API and permission requirements.
- Command execution is confirmed without logging secrets.
- Failure is reported if Termux is missing, permission is denied, or the API is unsupported.

Non-goal: Do not use repeated command invocations, shared-storage files, or broadcast result payloads as a substitute for a real-time full-duplex frame/input stream.

### B. Companion/plugin with a Binder service

Before implementing it, prove the identity and authorization model. A service running in a separate companion APK does not automatically gain access to the Termux process or its Unix sockets. The Termux-side component must actually host or broker the endpoint, and Android must allow the display host to bind under an explicit permission/signature policy.

Pass criteria:
- The service's hosting process and UID are known.
- An untrusted caller is rejected.
- An authorized client can send and receive multiple bounded messages in one session.
- Disconnect/reconnect, process death and backgrounding are handled.
- A sustained bidirectional test is measured on Android 10.
- The normal Termux install remains intact.

### C. Shared UID

Treat only as an optional controlled-build experiment. It requires compatible package identity, manifest/build configuration supported by the OS, and matching signing credentials across the participating packages. It is not a solution for arbitrary already-installed Termux distributions and must not be silently assumed.

## Device test matrix

| Test | Expected result |
|---|---|
| Termux absent | Clear actionable error; no crash |
| Required permission not granted | Clear denial; no repeated prompts |
| Authorized client | Authenticated handshake succeeds |
| Unauthorized package/caller | Rejected before session data is exposed |
| Multiple request/response messages | Both directions work in the same session |
| Payload over configured limit | Rejected without unbounded allocation |
| App backgrounded / process killed | Session closes cleanly and can reconnect |
| Android 10 target device | All required IPC checks pass without TCP/root/SELinux changes |

## Exit criteria

Do not replace the current transport or start real frame streaming until one candidate passes the authorization, bidirectional, lifecycle and Android 10 checks. Record device Android version, Termux distribution/version, endpoint UID, app UID, API result and sanitized logs. Never include the session token in logs or reports.
