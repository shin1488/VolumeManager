// VolumeManagerHelper / main.swift
//
// Bridges the Kotlin/Compose UI to the VolumeManagerDevice Audio Server
// Plug-in. The UI speaks JSON-over-stdio (or an XPC listener, TBD) to
// this helper; the helper uses CoreAudio to enumerate clients of the
// virtual device and read/write their per-client volume + mute properties.
//
// This file is intentionally a stub. The shape of the protocol is fixed
// (see `Command` / `Event`) so the Kotlin side can start consuming it
// before the CoreAudio plumbing lands.

import Foundation
import CoreAudio

// MARK: - Wire format ---------------------------------------------------------

struct Command: Codable {
    let op: String          // "list" | "setVolume" | "setMute" | "subscribe"
    let pid: Int32?
    let value: Double?
}

struct Session: Codable {
    let pid: Int32
    let name: String
    let volume: Double
    let muted: Bool
}

struct Event: Codable {
    let type: String        // "sessions" | "error"
    let sessions: [Session]?
    let message: String?
}

// MARK: - CoreAudio glue (TODO) -----------------------------------------------

enum DeviceAccess {
    /// TODO(macos): discover the VolumeManagerDevice by UID, query its
    /// custom `kAudioDevicePropertyClients`-equivalent property, and map
    /// the result into `[Session]`.
    static func snapshot() -> [Session] { [] }

    /// TODO(macos): call `AudioObjectSetPropertyData` with the scalar
    /// volume for the per-client volume control owned by `pid`.
    static func setVolume(pid: Int32, value: Double) {}

    /// TODO(macos): same as above but for the boolean mute control.
    static func setMute(pid: Int32, muted: Bool) {}
}

// MARK: - Event loop ----------------------------------------------------------

let decoder = JSONDecoder()
let encoder = JSONEncoder()

func emit(_ event: Event) {
    guard let data = try? encoder.encode(event),
          let line = String(data: data, encoding: .utf8) else { return }
    FileHandle.standardOutput.write(Data((line + "\n").utf8))
}

while let line = readLine() {
    guard let data = line.data(using: .utf8),
          let cmd  = try? decoder.decode(Command.self, from: data) else {
        emit(Event(type: "error", sessions: nil, message: "invalid command"))
        continue
    }

    switch cmd.op {
    case "list":
        emit(Event(type: "sessions", sessions: DeviceAccess.snapshot(), message: nil))

    case "setVolume":
        if let pid = cmd.pid, let v = cmd.value {
            DeviceAccess.setVolume(pid: pid, value: v)
        }

    case "setMute":
        if let pid = cmd.pid, let v = cmd.value {
            DeviceAccess.setMute(pid: pid, muted: v != 0)
        }

    case "subscribe":
        // TODO(macos): register an AudioObjectPropertyListener that emits
        //  "sessions" events whenever the client list or any client's
        //  volume/mute changes.
        break

    default:
        emit(Event(type: "error", sessions: nil, message: "unknown op: \(cmd.op)"))
    }
}
