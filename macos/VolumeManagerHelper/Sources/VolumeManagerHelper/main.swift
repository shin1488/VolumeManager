// VolumeManagerHelper / main.swift
//
// Audio forwarder: captures from VolumeManagerDevice's loopback input
// stream using one AUHAL AudioUnit and plays through the real default
// output device using another AUHAL AudioUnit. A render callback on
// the playback unit pulls samples from a ring buffer that the capture
// unit's input callback fills.
//
// Also hosts the JSON-over-stdio control bridge for the Kotlin UI
// (per-app volume stubs).

import AudioToolbox
import CoreAudio
import Foundation

// MARK: - Wire format ---------------------------------------------------------

struct Command: Codable {
    let op: String
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
    let type: String
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

    static func deviceSampleRate(_ dev: AudioDeviceID) -> Float64 {
        var rate: Float64 = 48000
        var size = UInt32(MemoryLayout<Float64>.size)
        var addr = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyNominalSampleRate,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain)
        AudioObjectGetPropertyData(dev, &addr, 0, nil, &size, &rate)
        return rate
    }
}

// MARK: - TPCircularBuffer-style ring buffer ----------------------------------

/// Simple SPSC ring buffer for Float32 samples. Uses monotonic
/// 64-bit counters (no wrap — overflow in ~3 million years at 48kHz).
final class RingBuffer {
    private let buf: UnsafeMutablePointer<Float>
    private let cap: Int
    private var w: Int = 0   // monotonic write position (in floats)
    private var r: Int = 0   // monotonic read position (in floats)

    init(floatCapacity: Int) {
        cap = floatCapacity
        buf = .allocate(capacity: floatCapacity)
        buf.initialize(repeating: 0, count: floatCapacity)
    }
    deinit { buf.deallocate() }

    var available: Int { w - r }

    func write(_ src: UnsafePointer<Float>, count: Int) {
        for i in 0..<count {
            buf[(w + i) % cap] = src[i]
        }
        w += count
    }

    func read(into dst: UnsafeMutablePointer<Float>, count: Int) {
        // If writer is way ahead, skip forward to stay close.
        if available > cap { r = w - cap / 2 }
        for i in 0..<count {
            if r + i < w {
                dst[i] = buf[(r + i) % cap]
            } else {
                dst[i] = 0  // underrun → silence
            }
        }
        r += count
    }
}

// MARK: - AUHAL forwarder -----------------------------------------------------

func log(_ msg: String) {
    FileHandle.standardError.write(
        Data("VolumeManagerHelper: \(msg)\n".utf8))
}

final class AudioForwarder {
    // Ring buffer: 2ch * 48000 * 2s = 192000 floats
    private let ring = RingBuffer(floatCapacity: 192_000)
    private var captureUnit:  AudioComponentInstance?
    private var playbackUnit: AudioComponentInstance?
    private var running = false

    func start() {
        guard !running else { return }

        guard let captureDevID = HAL.device(byUID: kVolumeManagerDeviceUID) else {
            log("capture device not found"); return
        }
        let playbackDevID = HAL.defaultOutputDevice()
        guard playbackDevID != captureDevID,
              playbackDevID != kAudioObjectUnknown else {
            log("default output unusable"); return
        }

        let capRate = HAL.deviceSampleRate(captureDevID)
        let outRate = HAL.deviceSampleRate(playbackDevID)
        log("capture device=\(captureDevID) rate=\(capRate)")
        log("playback device=\(playbackDevID) rate=\(outRate)")

        // Common ASBD: 2ch interleaved Float32 at capture device rate.
        var asbd = AudioStreamBasicDescription(
            mSampleRate: capRate,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked | kAudioFormatFlagsNativeEndian,
            mBytesPerPacket: 8,
            mFramesPerPacket: 1,
            mBytesPerFrame: 8,
            mChannelsPerFrame: 2,
            mBitsPerChannel: 32,
            mReserved: 0)

        // ---- Create capture AUHAL (input-only) ----
        var desc = AudioComponentDescription(
            componentType: kAudioUnitType_Output,
            componentSubType: kAudioUnitSubType_HALOutput,
            componentManufacturer: kAudioUnitManufacturer_Apple,
            componentFlags: 0, componentFlagsMask: 0)

        guard let comp = AudioComponentFindNext(nil, &desc) else {
            log("AUHAL component not found"); return
        }

        var capUnit: AudioComponentInstance?
        AudioComponentInstanceNew(comp, &capUnit)
        guard let capUnit = capUnit else { log("capture unit alloc failed"); return }

        // Enable input, disable output on capture unit.
        var one: UInt32 = 1
        var zero: UInt32 = 0
        AudioUnitSetProperty(capUnit, kAudioOutputUnitProperty_EnableIO,
                             kAudioUnitScope_Input, 1, &one, 4)
        AudioUnitSetProperty(capUnit, kAudioOutputUnitProperty_EnableIO,
                             kAudioUnitScope_Output, 0, &zero, 4)

        // Set capture device.
        var capDev = captureDevID
        AudioUnitSetProperty(capUnit, kAudioOutputUnitProperty_CurrentDevice,
                             kAudioUnitScope_Global, 0,
                             &capDev, UInt32(MemoryLayout<AudioDeviceID>.size))

        // Set format we want on the output side of the capture unit
        // (i.e., what we receive in the callback).
        AudioUnitSetProperty(capUnit, kAudioUnitProperty_StreamFormat,
                             kAudioUnitScope_Output, 1,
                             &asbd, UInt32(MemoryLayout<AudioStreamBasicDescription>.size))

        // Input callback: capture → ring buffer.
        let ringPtr = Unmanaged.passUnretained(ring).toOpaque()
        var inputCB = AURenderCallbackStruct(
            inputProc: { (inRefCon, ioFlags, inTimeStamp, inBusNumber, inFrames, _) -> OSStatus in
                let ring = Unmanaged<RingBuffer>.fromOpaque(inRefCon).takeUnretainedValue()

                // We need to pull audio from the capture unit. To do that
                // we call AudioUnitRender. But we need the capture unit
                // reference... we'll store it in a global. Not pretty but
                // this is a single-instance helper.
                guard let unit = AudioForwarder.sharedCaptureUnit else { return noErr }

                var bufList = AudioBufferList(
                    mNumberBuffers: 1,
                    mBuffers: AudioBuffer(
                        mNumberChannels: 2,
                        mDataByteSize: inFrames * 8,
                        mData: nil))

                // Allocate temp buffer.
                let tmp = UnsafeMutablePointer<Float>.allocate(capacity: Int(inFrames) * 2)
                defer { tmp.deallocate() }
                bufList.mBuffers.mData = UnsafeMutableRawPointer(tmp)

                let status = AudioUnitRender(unit, ioFlags, inTimeStamp, inBusNumber, inFrames, &bufList)
                if status == noErr {
                    ring.write(tmp, count: Int(inFrames) * 2)
                }
                return noErr
            },
            inputProcRefCon: ringPtr)
        AudioUnitSetProperty(capUnit, kAudioOutputUnitProperty_SetInputCallback,
                             kAudioUnitScope_Global, 0,
                             &inputCB, UInt32(MemoryLayout<AURenderCallbackStruct>.size))

        AudioForwarder.sharedCaptureUnit = capUnit
        captureUnit = capUnit

        // ---- Create playback AUHAL (output-only, default) ----
        var outUnit: AudioComponentInstance?
        AudioComponentInstanceNew(comp, &outUnit)
        guard let outUnit = outUnit else { log("playback unit alloc failed"); return }

        // Set playback device.
        var outDev = playbackDevID
        AudioUnitSetProperty(outUnit, kAudioOutputUnitProperty_CurrentDevice,
                             kAudioUnitScope_Global, 0,
                             &outDev, UInt32(MemoryLayout<AudioDeviceID>.size))

        // Set format on the input side of the playback unit.
        // Use capRate (not outRate!) because our ring buffer data
        // comes from the capture unit at capRate. The AUHAL will
        // internally sample-rate-convert capRate → outRate.
        var outAsbd = asbd  // already at capRate
        AudioUnitSetProperty(outUnit, kAudioUnitProperty_StreamFormat,
                             kAudioUnitScope_Input, 0,
                             &outAsbd, UInt32(MemoryLayout<AudioStreamBasicDescription>.size))

        // Render callback: ring buffer → playback.
        var renderCB = AURenderCallbackStruct(
            inputProc: { (inRefCon, _, _, _, inFrames, ioData) -> OSStatus in
                let ring = Unmanaged<RingBuffer>.fromOpaque(inRefCon).takeUnretainedValue()
                guard let ioData = ioData else { return noErr }
                let buffers = UnsafeMutableAudioBufferListPointer(ioData)
                if buffers.count == 1 && buffers[0].mNumberChannels >= 2 {
                    // Interleaved
                    if let data = buffers[0].mData {
                        ring.read(into: data.assumingMemoryBound(to: Float.self),
                                  count: Int(inFrames) * 2)
                    }
                } else if buffers.count >= 2 {
                    // Non-interleaved: deinterleave from ring
                    let tmp = UnsafeMutablePointer<Float>.allocate(capacity: Int(inFrames) * 2)
                    defer { tmp.deallocate() }
                    ring.read(into: tmp, count: Int(inFrames) * 2)
                    for c in 0..<min(2, buffers.count) {
                        if let dst = buffers[c].mData?.assumingMemoryBound(to: Float.self) {
                            for i in 0..<Int(inFrames) {
                                dst[i] = tmp[i * 2 + c]
                            }
                        }
                    }
                }
                return noErr
            },
            inputProcRefCon: ringPtr)
        AudioUnitSetProperty(outUnit, kAudioUnitProperty_SetRenderCallback,
                             kAudioUnitScope_Input, 0,
                             &renderCB, UInt32(MemoryLayout<AURenderCallbackStruct>.size))

        playbackUnit = outUnit

        // ---- Initialize and start both units ----
        var err = AudioUnitInitialize(capUnit)
        if err != noErr { log("capture init failed: \(err)"); return }
        err = AudioUnitInitialize(outUnit)
        if err != noErr { log("playback init failed: \(err)"); return }

        err = AudioOutputUnitStart(capUnit)
        if err != noErr { log("capture start failed: \(err)"); return }
        err = AudioOutputUnitStart(outUnit)
        if err != noErr { log("playback start failed: \(err)"); return }

        running = true
        log("forwarder running OK")
    }

    func stop() {
        if let u = captureUnit  { AudioOutputUnitStop(u); AudioComponentInstanceDispose(u) }
        if let u = playbackUnit { AudioOutputUnitStop(u); AudioComponentInstanceDispose(u) }
        running = false
    }

    // Shared reference so the input callback can call AudioUnitRender.
    static var sharedCaptureUnit: AudioComponentInstance?
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
    case "setVolume", "setMute", "subscribe":
        break
    default:
        emit(Event(type: "error", sessions: nil, message: "unknown op: \(cmd.op)"))
    }
}

forwarder.stop()
