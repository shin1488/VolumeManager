// VolumeManagerHelper / main.swift
//
// Two responsibilities:
//
//  1. Audio forwarder: uses AVAudioEngine to capture from VolumeManager-
//     Device (loopback input stream) and play through the real default
//     output. AVAudioEngine handles sample rate conversion, buffer
//     management and format adaptation automatically.
//
//  2. Control bridge: JSON-over-stdio protocol for the Kotlin UI to
//     list sessions and adjust per-app volume (stub until driver-side
//     per-client properties land).

import AVFoundation
import CoreAudio
import Foundation

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
    let type: String        // "sessions" | "error" | "status"
    let sessions: [Session]?
    let message: String?
}

// MARK: - CoreAudio helpers ---------------------------------------------------

let kVolumeManagerDeviceUID = "VolumeManagerDevice_UID"

enum HAL {
    static func device(byUID uid: String) -> AudioDeviceID? {
        var deviceID = AudioDeviceID(kAudioObjectUnknown)
        var cfUID: CFString = uid as CFString

        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDeviceForUID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain)
        var size = UInt32(MemoryLayout<AudioValueTranslation>.size)

        let err = withUnsafeMutablePointer(to: &cfUID) { uidPtr in
            withUnsafeMutablePointer(to: &deviceID) { devPtr in
                var translation = AudioValueTranslation(
                    mInputData: uidPtr,
                    mInputDataSize: UInt32(MemoryLayout<CFString>.size),
                    mOutputData: devPtr,
                    mOutputDataSize: UInt32(MemoryLayout<AudioDeviceID>.size))
                return withUnsafeMutablePointer(to: &translation) { tPtr -> OSStatus in
                    AudioObjectGetPropertyData(
                        AudioObjectID(kAudioObjectSystemObject),
                        &address, 0, nil, &size, tPtr)
                }
            }
        }
        guard err == noErr, deviceID != kAudioObjectUnknown else { return nil }
        return deviceID
    }

    static func defaultOutputDevice() -> AudioDeviceID {
        var id = AudioDeviceID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain)
        AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &address, 0, nil, &size, &id)
        return id
    }
}

// MARK: - AVAudioEngine forwarder ---------------------------------------------

final class AudioForwarder {
    private var engine: AVAudioEngine?
    private var running = false

    func start() {
        guard !running else { return }

        guard let captureID = HAL.device(byUID: kVolumeManagerDeviceUID) else {
            log("capture device not found")
            return
        }
        let playbackID = HAL.defaultOutputDevice()
        if playbackID == captureID || playbackID == kAudioObjectUnknown {
            log("default output not usable (same as capture or unknown)")
            return
        }

        let eng = AVAudioEngine()

        // --- Point input node at VolumeManagerDevice ---
        do {
            let inputNode = eng.inputNode
            guard let inputAU = inputNode.audioUnit else {
                log("no audio unit on input node")
                return
            }
            var capID = captureID
            let status1 = AudioUnitSetProperty(
                inputAU,
                kAudioOutputUnitProperty_CurrentDevice,
                kAudioUnitScope_Global, 0,
                &capID, UInt32(MemoryLayout<AudioDeviceID>.size))
            if status1 != noErr {
                log("failed to set capture device: \(status1)")
                return
            }

            // --- Point output node at real default output ---
            let outputNode = eng.outputNode
            guard let outputAU = outputNode.audioUnit else {
                log("no audio unit on output node")
                return
            }
            var outID = playbackID
            let status2 = AudioUnitSetProperty(
                outputAU,
                kAudioOutputUnitProperty_CurrentDevice,
                kAudioUnitScope_Global, 0,
                &outID, UInt32(MemoryLayout<AudioDeviceID>.size))
            if status2 != noErr {
                log("failed to set playback device: \(status2)")
                return
            }

            // --- Connect: input → mainMixer → output ---
            // Use the input node's hardware format; AVAudioEngine does
            // any needed sample-rate / channel-count conversion.
            let hwFormat = inputNode.inputFormat(forBus: 0)
            log("input hw format: \(hwFormat)")

            eng.connect(inputNode, to: eng.mainMixerNode, format: hwFormat)

            eng.prepare()
            try eng.start()

            engine  = eng
            running = true
            log("forwarder running (capture=\(captureID) playback=\(playbackID) rate=\(hwFormat.sampleRate))")
        } catch {
            log("engine start failed: \(error)")
        }
    }

    func stop() {
        engine?.stop()
        engine  = nil
        running = false
    }

    private func log(_ msg: String) {
        FileHandle.standardError.write(
            Data("VolumeManagerHelper: \(msg)\n".utf8))
    }
}

// MARK: - Event loop ----------------------------------------------------------

let decoder = JSONDecoder()
let encoder = JSONEncoder()

func emit(_ event: Event) {
    guard let data = try? encoder.encode(event),
          let line = String(data: data, encoding: .utf8) else { return }
    FileHandle.standardOutput.write(Data((line + "\n").utf8))
}

let forwarder = AudioForwarder()
forwarder.start()
emit(Event(type: "status", sessions: nil, message: "forwarder started"))

while let line = readLine() {
    guard let data = line.data(using: .utf8),
          let cmd  = try? decoder.decode(Command.self, from: data) else {
        emit(Event(type: "error", sessions: nil, message: "invalid command"))
        continue
    }

    switch cmd.op {
    case "list":
        emit(Event(type: "sessions", sessions: [], message: nil))
    case "setVolume", "setMute":
        break
    case "subscribe":
        break
    default:
        emit(Event(type: "error", sessions: nil, message: "unknown op: \(cmd.op)"))
    }
}

forwarder.stop()
