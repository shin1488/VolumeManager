# macOS Port Roadmap

This document tracks the work needed to bring VolumeManager to macOS with
the **same UI/UX** as the Windows build: one floating icon column listing
every app that is currently making sound, with a click-to-reveal volume
slider and mute toggle per app.

> This branch (`feature/macos-hal`) is scaffolding only. Nothing listed
> below is functional yet. README is intentionally untouched until the
> port actually works.

## Why this is more work than "just swap the audio API"

Windows' `IAudioSessionManager2` (WASAPI) hands out one audio session per
process with independent volume and mute. macOS CoreAudio does **not**
expose that — volume is per-device, not per-process. Every app that
ships per-app volume on macOS does so by installing a custom **Audio
Server Plug-in** (a virtual output device) and mixing with gain applied
per client inside the driver. We are taking the same route.

Reference implementations to crib from:

- Apple's `NullAudio` / `SimpleAudioDriver` samples (CoreAudio/AudioServerPlugIn.h)
- [BackgroundMusic](https://github.com/kyleneideck/BackgroundMusic) – the
  canonical open-source BGMDriver + BGMApp pair. Our device is modeled on
  its shape, without any of its code.

## Components

```
macos/
├── VolumeManagerDevice/     # Audio Server Plug-in (C++) — the virtual device
│   ├── Info.plist
│   └── src/VolumeManagerPlugIn.cpp
├── VolumeManagerHelper/     # Swift helper (XPC / stdio bridge)
│   ├── Package.swift
│   └── Sources/VolumeManagerHelper/main.swift
└── ROADMAP.md               # this file
```

And on the Kotlin side:

```
composeApp/src/jvmMain/kotlin/com/shin/volumemanager/audio/
├── AudioSessionService.kt         # interface + factory
├── WindowsAudioSessionService.kt  # existing WASAPI impl (unchanged)
├── MacAudioSessionService.kt      # spawns the helper, talks JSON
└── ...
```

## Milestones

### M1 — UI runs on macOS (no audio)
- [x] Extract `AudioSessionService` interface, pick impl by OS.
- [x] Add `MacAudioSessionService` stub that emits an empty session list.
- [ ] Add a `macosX64` / `macosArm64` Compose Desktop distribution target
      in `build.gradle.kts` so we can produce a runnable `.app`.
- [ ] Manual smoke test: launch on macOS, confirm the floating window
      opens and mirrors the Windows UI with zero sessions.

### M2 — Helper process contract
- [x] Define JSON protocol (`list` / `setVolume` / `setMute` / `subscribe`).
- [x] Kotlin side spawns helper if `VOLUMEMANAGER_HELPER` env var is set.
- [ ] Parse `sessions` / `error` events and push into `_sessions`.
- [ ] Emit an integration test with a mocked helper that replies with
      canned JSON so the UI can be driven end-to-end without the driver.

### M3 — Audio Server Plug-in bring-up
- [ ] Build `VolumeManagerDevice.driver` from `src/VolumeManagerPlugIn.cpp`.
- [ ] Generate a real factory UUID, mirror it in `Info.plist` and the
      `.cpp` file.
- [ ] Implement the `Null`-level property plumbing: device / stream /
      default format. At this point the device must appear in
      `System Settings → Sound → Output`.
- [ ] Add a single global volume control object first to validate the
      property listener flow end-to-end.

### M4 — Per-client volume
- [ ] Track clients via `AddDeviceClient` / `RemoveDeviceClient` and
      store per-pid gain + mute in a shared struct.
- [ ] Apply gain in `DoIOOperation` before forwarding buffers.
- [ ] Expose a custom property (`kVolumeManager_ClientListProperty`)
      that the helper can read to snapshot the client table.
- [ ] Expose per-client volume / mute as writable custom properties so
      the helper can push updates from the UI.

### M5 — Helper ↔ driver
- [ ] Helper uses `AudioObjectGetPropertyData` against the custom
      properties to answer `list`.
- [ ] Helper uses `AudioObjectSetPropertyData` to implement
      `setVolume` / `setMute`.
- [ ] Helper registers an `AudioObjectPropertyListenerBlock` so the
      `subscribe` stream delivers real-time updates.

### M6 — Packaging & installer
- [ ] Ship the driver + helper inside the `.app` bundle and install the
      driver to `~/Library/Audio/Plug-Ins/HAL/` on first launch.
- [ ] Sign and notarize both binaries.
- [ ] Surface a first-run setup screen that explains the virtual device
      and asks the user to switch their default output.

### M7 — README & release
- [ ] Only once M1–M6 are working on a real Mac: update the top-level
      README with a macOS section, upload a notarized DMG to `releases/`.

## Known risks

- **Signing.** A custom Audio Server Plug-in needs either a developer
  account for notarization or the user disabling SIP. Decide how we want
  to handle this before M6.
- **Sample rate / channel negotiation.** Users with weird multi-channel
  setups will expose bugs in the `StreamFormat` plumbing; the Windows
  build doesn't have this category of issue.
- **Apple Silicon vs Intel.** Both architectures need to be tested; the
  driver should be a universal binary.

## Out of scope (for now)

- Input device / microphone volume control.
- Per-app EQ or routing.
- Replacing the system default output automatically.
