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

/// Lock-free SPSC ring buffer. Stores interleaved Float32 stereo frames.
/// Write head and read head are monotonic 64-bit counters; slot =
/// counter mod capacity. The consumer skips ahead if it falls too far
/// behind (overflow protection), and outputs silence when it catches
/// up (underrun protection).
final class RingBuffer {
    let capacity: Int          // in frames
    let channels: Int
    private let storage: UnsafeMutablePointer<Float>
    // Volatile-ish via class reference semantics on 64-bit arm64.
    private(set) var writeHead: Int = 0
    private(set) var readHead:  Int = 0

    init(capacityFrames: Int, channels: Int) {
        self.capacity = capacityFrames
        self.channels = channels
        self.storage  = .allocate(capacity: capacityFrames * channels)
        self.storage.initialize(repeating: 0, count: capacityFrames * channels)
    }
    deinit { storage.deallocate() }

    /// Store interleaved frames from `src`.
    func store(_ src: UnsafePointer<Float>, frames: Int) {
        for i in 0..<frames {
            let slot = writeHead % capacity
            let off  = slot * channels
            for c in 0..<channels {
                storage[off + c] = src[i * channels + c]
            }
            writeHead &+= 1
        }
    }

    /// Store non-interleaved (planar) buffers — one pointer per channel.
    func storePlanar(_ ptrs: [UnsafePointer<Float>], frames: Int) {
        for i in 0..<frames {
            let slot = writeHead % capacity
            let off  = slot * channels
            for c in 0..<min(channels, ptrs.count) {
                storage[off + c] = ptrs[c][i]
            }
            writeHead &+= 1
        }
    }

    /// Fetch interleaved frames into `dst`.
    func fetch(into dst: UnsafeMutablePointer<Float>, frames: Int) {
        // If writer is way ahead, skip to stay close.
        let available = writeHead &- readHead
        if available > capacity {
            readHead = writeHead &- capacity / 2
        }
        for i in 0..<frames {
            if readHead >= writeHead {
                // underrun → silence
                for c in 0..<channels { dst[i * channels + c] = 0 }
            } else {
                let slot = readHead % capacity
                let off  = slot * channels
                for c in 0..<channels {
                    dst[i * channels + c] = storage[off + c]
                }
                readHead &+= 1
            }
        }
    }

    /// Fetch into separate per-channel (planar) buffers.
    func fetchPlanar(into ptrs: [UnsafeMutablePointer<Float>], frames: Int) {
        let available = writeHead &- readHead
        if available > capacity {
            readHead = writeHead &- capacity / 2
        }
        for i in 0..<frames {
            if readHead >= writeHead {
                for c in 0..<min(channels, ptrs.count) { ptrs[c][i] = 0 }
            } else {
                let slot = readHead % capacity
                let off  = slot * channels
                for c in 0..<min(channels, ptrs.count) {
                    ptrs[c][i] = storage[off + c]
                }
                readHead &+= 1
            }
        }
    }
}

// MARK: - Audio forwarder -----------------------------------------------------

final class AudioForwarder {
    // 32768 frames ≈ 0.68s at 48kHz — enough to absorb jitter.
    private let ring = RingBuffer(capacityFrames: 32768, channels: 2)
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

        // --- Capture IOProc: VolumeManagerDevice input → ring buffer ---
        AudioDeviceCreateIOProcID(captureDevice, { (_, _, inputData, _, _, _, ctx) -> OSStatus in
            guard let ctx = ctx else { return noErr }
            let fwd = Unmanaged<AudioForwarder>.fromOpaque(ctx).takeUnretainedValue()
            let buffers = UnsafeMutableAudioBufferListPointer(
                UnsafeMutablePointer(mutating: inputData))

            if buffers.count == 1 && buffers[0].mNumberChannels == 2 {
                // Interleaved stereo — our virtual device's format.
                if let data = buffers[0].mData {
                    let frames = Int(buffers[0].mDataByteSize) /
                                 (MemoryLayout<Float>.size * Int(buffers[0].mNumberChannels))
                    fwd.ring.store(data.assumingMemoryBound(to: Float.self), frames: frames)
                }
            } else if buffers.count >= 2 {
                // Non-interleaved (planar) — one buffer per channel.
                let frames = Int(buffers[0].mDataByteSize) / MemoryLayout<Float>.size
                var ptrs: [UnsafePointer<Float>] = []
                for i in 0..<min(2, buffers.count) {
                    if let d = buffers[i].mData {
                        ptrs.append(d.assumingMemoryBound(to: Float.self))
                    }
                }
                if ptrs.count == 2 {
                    fwd.ring.storePlanar(ptrs, frames: frames)
                }
            }
            return noErr
        }, selfRef, &captureProcID)

        // --- Playback IOProc: ring buffer → real output device ---
        AudioDeviceCreateIOProcID(playbackDevice, { (_, _, _, _, outputData, _, ctx) -> OSStatus in
            guard let ctx = ctx else { return noErr }
            let fwd = Unmanaged<AudioForwarder>.fromOpaque(ctx).takeUnretainedValue()
            let buffers = UnsafeMutableAudioBufferListPointer(outputData)

            if buffers.count == 1 && buffers[0].mNumberChannels >= 2 {
                // Interleaved
                if let data = buffers[0].mData {
                    let frames = Int(buffers[0].mDataByteSize) /
                                 (MemoryLayout<Float>.size * Int(buffers[0].mNumberChannels))
                    fwd.ring.fetch(into: data.assumingMemoryBound(to: Float.self), frames: frames)
                }
            } else if buffers.count >= 2 {
                // Non-interleaved (planar) — common for built-in speakers.
                let frames = Int(buffers[0].mDataByteSize) / MemoryLayout<Float>.size
                var ptrs: [UnsafeMutablePointer<Float>] = []
                for i in 0..<min(2, buffers.count) {
                    if let d = buffers[i].mData {
                        ptrs.append(d.assumingMemoryBound(to: Float.self))
                    }
                }
                if ptrs.count >= 2 {
                    fwd.ring.fetchPlanar(into: ptrs, frames: frames)
                }
            }
            return noErr
        }, selfRef, &playbackProcID)

        // Start capture first so ring buffer has data before playback reads.
        if let c = captureProcID  { AudioDeviceStart(captureDevice,  c) }
        // Small pre-roll delay to avoid initial underrun.
        usleep(50_000) // 50ms
        if let p = playbackProcID { AudioDeviceStart(playbackDevice, p) }
        running = true

        FileHandle.standardError.write(Data(
            "VolumeManagerHelper: forwarder running (capture=\(captureDevice) playback=\(playbackDevice))\n".utf8))
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
