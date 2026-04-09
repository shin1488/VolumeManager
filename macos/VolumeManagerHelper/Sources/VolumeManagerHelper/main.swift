// VolumeManagerHelper / main.swift
//
// Two responsibilities:
//
//  1. Control bridge: speaks a small JSON-over-stdio protocol so the
//     Kotlin/Compose UI can list sessions and drive per-app volume.
//     Right now this is still a stub (the driver side exposes only a
//     single master volume); the wire format is fixed so the UI can
//     plug in without waiting for the CoreAudio glue.
//
//  2. Audio forwarder: VolumeManagerDevice is a loopback virtual
//     device. When the user picks it as the system output, selecting
//     it alone would mute everything. This helper opens
//     VolumeManagerDevice as an *input* and copies the captured audio
//     to whatever the current default output device is (skipping
//     VolumeManagerDevice itself so we don't feedback-loop). The copy
//     is done through a tiny lock-free-ish ring buffer shared by the
//     two IOProcs.

import Foundation
import CoreAudio
import AudioToolbox

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

/// UID of the virtual device exported by VolumeManagerDevice.driver.
/// Must match `kDevice_UID` in VolumeManagerPlugIn.cpp.
let kVolumeManagerDeviceUID = "VolumeManagerDevice_UID"

enum HAL {
    /// Look up an AudioDeviceID by its UID. Returns `nil` when nothing
    /// matches, typically because the driver hasn't loaded yet.
    static func device(byUID uid: String) -> AudioDeviceID? {
        var translation = AudioValueTranslation(
            mInputData: Unmanaged.passUnretained(uid as CFString).toOpaque(),
            mInputDataSize: UInt32(MemoryLayout<CFString>.size),
            mOutputData: UnsafeMutableRawPointer.allocate(
                byteCount: MemoryLayout<AudioDeviceID>.size, alignment: 1),
            mOutputDataSize: UInt32(MemoryLayout<AudioDeviceID>.size))
        defer { translation.mOutputData.deallocate() }

        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDeviceForUID,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain)
        var size = UInt32(MemoryLayout<AudioValueTranslation>.size)

        let err = withUnsafeMutablePointer(to: &translation) { ptr -> OSStatus in
            AudioObjectGetPropertyData(
                AudioObjectID(kAudioObjectSystemObject),
                &address, 0, nil, &size, ptr)
        }
        guard err == noErr else { return nil }
        let id = translation.mOutputData.load(as: AudioDeviceID.self)
        return id == kAudioObjectUnknown ? nil : id
    }

    /// Current system default output device. Updated on
    /// `kAudioHardwarePropertyDefaultOutputDevice` changes.
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

// MARK: - Ring buffer ---------------------------------------------------------

/// Fixed-size interleaved Float32 ring buffer. One producer (capture
/// IOProc) and one consumer (playback IOProc). No locks; relies on
/// atomic 64-bit integer stores on modern macOS. Good enough for a
/// 2ch/44.1k loopback.
final class RingBuffer {
    let capacityFrames: Int
    let channels: Int
    private let storage: UnsafeMutablePointer<Float>
    private var write: UInt64 = 0   // monotonic frame counter
    private var read:  UInt64 = 0

    init(capacityFrames: Int, channels: Int) {
        self.capacityFrames = capacityFrames
        self.channels       = channels
        self.storage = UnsafeMutablePointer<Float>.allocate(
            capacity: capacityFrames * channels)
        self.storage.initialize(repeating: 0, count: capacityFrames * channels)
    }

    deinit { storage.deallocate() }

    func write(_ src: UnsafePointer<Float>, frames: Int) {
        for i in 0..<frames {
            let slot = Int(write % UInt64(capacityFrames))
            for c in 0..<channels {
                storage[slot * channels + c] = src[i * channels + c]
            }
            write &+= 1
        }
    }

    func read(into dst: UnsafeMutablePointer<Float>, frames: Int) {
        // Underrun protection: if read caught up with write, output silence.
        for i in 0..<frames {
            if read >= write {
                for c in 0..<channels { dst[i * channels + c] = 0 }
            } else {
                let slot = Int(read % UInt64(capacityFrames))
                for c in 0..<channels {
                    dst[i * channels + c] = storage[slot * channels + c]
                }
                read &+= 1
            }
        }
    }
}

// MARK: - Audio forwarder -----------------------------------------------------

final class AudioForwarder {
    private let ring = RingBuffer(capacityFrames: 16384, channels: 2)
    private var captureDevice:  AudioDeviceID = 0
    private var playbackDevice: AudioDeviceID = 0
    private var captureProcID:  AudioDeviceIOProcID?
    private var playbackProcID: AudioDeviceIOProcID?
    private var running = false

    func start() {
        guard !running else { return }
        guard let cap = HAL.device(byUID: kVolumeManagerDeviceUID) else {
            FileHandle.standardError.write(Data(
                "VolumeManagerHelper: capture device not found\n".utf8))
            return
        }
        let out = HAL.defaultOutputDevice()
        if out == cap || out == kAudioObjectUnknown {
            FileHandle.standardError.write(Data(
                "VolumeManagerHelper: default output not set or is our own device\n".utf8))
            return
        }
        captureDevice  = cap
        playbackDevice = out

        let selfRef = Unmanaged.passUnretained(self).toOpaque()

        // Capture IOProc: reads from VolumeManagerDevice input stream.
        AudioDeviceCreateIOProcID(captureDevice, { (_, _, inputData, _, _, _, ctx) -> OSStatus in
            guard let ctx = ctx else { return noErr }
            let fwd = Unmanaged<AudioForwarder>.fromOpaque(ctx).takeUnretainedValue()
            let bufList = inputData.pointee
            if bufList.mNumberBuffers >= 1 {
                let buf = withUnsafePointer(to: bufList.mBuffers) { $0.pointee }
                if let data = buf.mData {
                    let frames = Int(buf.mDataByteSize) /
                                 MemoryLayout<Float>.size /
                                 Int(buf.mNumberChannels)
                    fwd.ring.write(data.assumingMemoryBound(to: Float.self),
                                   frames: frames)
                }
            }
            return noErr
        }, selfRef, &captureProcID)

        // Playback IOProc: writes to the real default output device.
        AudioDeviceCreateIOProcID(playbackDevice, { (_, _, _, _, outputData, _, ctx) -> OSStatus in
            guard let ctx = ctx, let outputData = outputData else { return noErr }
            let fwd = Unmanaged<AudioForwarder>.fromOpaque(ctx).takeUnretainedValue()
            let bufList = outputData.pointee
            if bufList.mNumberBuffers >= 1 {
                let buf = withUnsafePointer(to: bufList.mBuffers) { $0.pointee }
                if let data = buf.mData {
                    let frames = Int(buf.mDataByteSize) /
                                 MemoryLayout<Float>.size /
                                 Int(buf.mNumberChannels)
                    fwd.ring.read(into: data.assumingMemoryBound(to: Float.self),
                                  frames: frames)
                }
            }
            return noErr
        }, selfRef, &playbackProcID)

        if let c = captureProcID  { AudioDeviceStart(captureDevice,  c) }
        if let p = playbackProcID { AudioDeviceStart(playbackDevice, p) }
        running = true

        FileHandle.standardError.write(Data(
            "VolumeManagerHelper: forwarder running\n".utf8))
    }

    func stop() {
        guard running else { return }
        if let c = captureProcID {
            AudioDeviceStop(captureDevice, c)
            AudioDeviceDestroyIOProcID(captureDevice, c)
        }
        if let p = playbackProcID {
            AudioDeviceStop(playbackDevice, p)
            AudioDeviceDestroyIOProcID(playbackDevice, p)
        }
        running = false
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

// Start forwarding as soon as we launch so audio flows without waiting
// for a "start" command from the UI.
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
        // TODO(macos): enumerate real per-app sessions via a custom
        // device property exposed by the driver.
        emit(Event(type: "sessions", sessions: [], message: nil))

    case "setVolume":
        // TODO(macos): set per-pid gain on the driver via
        // AudioObjectSetPropertyData with a custom selector.
        break

    case "setMute":
        break

    case "subscribe":
        // TODO(macos): register AudioObjectPropertyListener on the
        // driver's client list and emit sessions events on change.
        break

    default:
        emit(Event(type: "error", sessions: nil, message: "unknown op: \(cmd.op)"))
    }
}

forwarder.stop()
