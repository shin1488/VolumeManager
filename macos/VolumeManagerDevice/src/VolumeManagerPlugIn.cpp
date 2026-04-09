// VolumeManagerPlugIn.cpp
//
// Minimum-viable macOS Audio Server Plug-in for VolumeManager.
//
// Goals of this file, in order of priority:
//   1. coreaudiod must be able to load the bundle, query the required
//      PlugIn / Device / Stream properties, and show the device in
//      System Settings → Sound → Output.
//   2. The device must advertise a single output stream at 44100 Hz,
//      2 channels, 32-bit float, so system audio can actually be
//      routed through it.
//   3. DoIOOperation must accept WriteMix and be ready to apply a
//      per-client gain before forwarding the buffer downstream.
//
// What this file intentionally does NOT do (yet):
//   - Forward audio to the real default output device. For now
//     DoIOOperation is a no-op sink, so selecting VolumeManagerDevice
//     as the system output will mute everything. That is fine as a
//     loading test; the full mixer comes next.
//   - Persist per-app volume to disk.
//   - Talk to the XPC helper. The hook is stubbed.
//
// Structure is deliberately flat: one file, static globals, only the
// object IDs / selectors we use. If it grows past ~1500 lines we'll
// split it along the NullAudio / BGM lines.

#include <CoreAudio/AudioServerPlugIn.h>
#include <CoreFoundation/CoreFoundation.h>
#include <mach/mach_time.h>
#include <math.h>
#include <os/log.h>
#include <pthread.h>
#include <string.h>

#define kPlugIn_BundleID          "com.shin.volumemanager.device"
#define kDevice_UID               "VolumeManagerDevice_UID"
#define kDevice_ModelUID          "VolumeManagerDevice_Model"
#define kDevice_Name              "VolumeManager"
#define kDevice_Manufacturer      "shin"
#define kPlugIn_Manufacturer      "shin"
#define kDevice_SampleRate        44100.0
#define kDevice_RingBufferSize    16384
#define kDevice_NumChannels       2

// Stable object IDs. 1 is always the plug-in object itself.
enum {
    kObjectID_PlugIn         = kAudioObjectPlugInObject, // == 1
    kObjectID_Device         = 2,
    kObjectID_Stream_Output  = 3,
    kObjectID_Volume_Output  = 4,
    kObjectID_Mute_Output    = 5,
};

// ---------- Driver-wide state -------------------------------------------------

// Single global driver interface table (vtable of callbacks).
static AudioServerPlugInDriverInterface  gDriverInterface;
// Double-pointer handed back to coreaudiod. AudioServerPlugInDriverRef is
// AudioServerPlugInDriverInterface**, so gDriverRef must be `&gDriverPtr`.
static AudioServerPlugInDriverInterface* gDriverPtr = &gDriverInterface;
static AudioServerPlugInDriverRef        gDriverRef = &gDriverPtr;

static AudioServerPlugInHostRef          gHost          = nullptr;
static pthread_mutex_t                   gStateLock     = PTHREAD_MUTEX_INITIALIZER;
static os_log_t                          gLog           = OS_LOG_DEFAULT;

// Mutable device state.
static Float64                           gSampleRate    = kDevice_SampleRate;
static UInt64                            gHostTicksPerFrame = 0;
static UInt64                            gNumberTimeStamps  = 0;
static Float64                           gAnchorHostTime    = 0;
static Float32                           gMasterVolume      = 1.0f;
static bool                              gMasterMute        = false;
static UInt32                            gIOIsRunning       = 0;

// ---------- Small helpers -----------------------------------------------------

static inline bool AddrMatch(const AudioObjectPropertyAddress* a,
                             AudioObjectPropertySelector sel) {
    return a && a->mSelector == sel;
}

static OSStatus WriteString(void* outData, UInt32 inDataSize, UInt32* outDataSize,
                            CFStringRef str) {
    if (inDataSize < sizeof(CFStringRef)) return kAudioHardwareBadPropertySizeError;
    *(CFStringRef*)outData = (CFStringRef)CFRetain(str);
    if (outDataSize) *outDataSize = sizeof(CFStringRef);
    return noErr;
}

// ---------- IUnknown plumbing -------------------------------------------------

static ULONG VolumeManager_AddRef(void* /*inDriver*/)  { return 1; }
static ULONG VolumeManager_Release(void* /*inDriver*/) { return 1; }

static HRESULT VolumeManager_QueryInterface(void* inDriver, REFIID inUUID, LPVOID* outInterface) {
    CFUUIDRef requestedUUID = CFUUIDCreateFromUUIDBytes(kCFAllocatorDefault, inUUID);
    if (!requestedUUID) return E_NOINTERFACE;

    HRESULT result = E_NOINTERFACE;
    if (CFEqual(requestedUUID, kAudioServerPlugInDriverInterfaceUUID) ||
        CFEqual(requestedUUID, IUnknownUUID)) {
        VolumeManager_AddRef(inDriver);
        if (outInterface) *outInterface = gDriverRef;
        result = 0; // S_OK
    } else if (outInterface) {
        *outInterface = nullptr;
    }
    CFRelease(requestedUUID);
    return result;
}

// ---------- Lifecycle ---------------------------------------------------------

static OSStatus VolumeManager_Initialize(AudioServerPlugInDriverRef /*inDriver*/,
                                         AudioServerPlugInHostRef   inHost) {
    pthread_mutex_lock(&gStateLock);
    gHost = inHost;

    // Host ticks per audio frame, for GetZeroTimeStamp.
    struct mach_timebase_info tbi;
    mach_timebase_info(&tbi);
    Float64 hostTicksPerNanos = (Float64)tbi.denom / (Float64)tbi.numer;
    gHostTicksPerFrame = (UInt64)((1.0e9 / gSampleRate) * hostTicksPerNanos);
    pthread_mutex_unlock(&gStateLock);

    os_log(gLog, "VolumeManagerDevice: Initialize OK (rate=%.0f)", gSampleRate);
    return noErr;
}

static OSStatus VolumeManager_CreateDevice(AudioServerPlugInDriverRef,
                                           CFDictionaryRef,
                                           const AudioServerPlugInClientInfo*,
                                           AudioObjectID*) {
    return kAudioHardwareUnsupportedOperationError;
}
static OSStatus VolumeManager_DestroyDevice(AudioServerPlugInDriverRef, AudioObjectID) {
    return kAudioHardwareUnsupportedOperationError;
}

static OSStatus VolumeManager_AddDeviceClient(AudioServerPlugInDriverRef,
                                              AudioObjectID,
                                              const AudioServerPlugInClientInfo*) {
    return noErr;
}
static OSStatus VolumeManager_RemoveDeviceClient(AudioServerPlugInDriverRef,
                                                 AudioObjectID,
                                                 const AudioServerPlugInClientInfo*) {
    return noErr;
}
static OSStatus VolumeManager_PerformDeviceConfigurationChange(AudioServerPlugInDriverRef,
                                                               AudioObjectID,
                                                               UInt64, void*) { return noErr; }
static OSStatus VolumeManager_AbortDeviceConfigurationChange(AudioServerPlugInDriverRef,
                                                             AudioObjectID,
                                                             UInt64, void*) { return noErr; }

// ---------- Property: HasProperty --------------------------------------------

static Boolean VolumeManager_HasProperty(AudioServerPlugInDriverRef,
                                         AudioObjectID inObjectID,
                                         pid_t,
                                         const AudioObjectPropertyAddress* inAddress) {
    if (!inAddress) return false;
    switch (inObjectID) {
    case kObjectID_PlugIn:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioObjectPropertyManufacturer:
        case kAudioObjectPropertyOwnedObjects:
        case kAudioPlugInPropertyDeviceList:
        case kAudioPlugInPropertyTranslateUIDToDevice:
        case kAudioPlugInPropertyResourceBundle:
            return true;
        }
        return false;

    case kObjectID_Device:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioObjectPropertyName:
        case kAudioObjectPropertyManufacturer:
        case kAudioObjectPropertyOwnedObjects:
        case kAudioDevicePropertyDeviceUID:
        case kAudioDevicePropertyModelUID:
        case kAudioDevicePropertyTransportType:
        case kAudioDevicePropertyRelatedDevices:
        case kAudioDevicePropertyClockDomain:
        case kAudioDevicePropertyDeviceIsAlive:
        case kAudioDevicePropertyDeviceIsRunning:
        case kAudioObjectPropertyControlList:
        case kAudioDevicePropertyDeviceCanBeDefaultDevice:
        case kAudioDevicePropertyDeviceCanBeDefaultSystemDevice:
        case kAudioDevicePropertyLatency:
        case kAudioDevicePropertyStreams:
        case kAudioDevicePropertySafetyOffset:
        case kAudioDevicePropertyNominalSampleRate:
        case kAudioDevicePropertyAvailableNominalSampleRates:
        case kAudioDevicePropertyIsHidden:
        case kAudioDevicePropertyZeroTimeStampPeriod:
        case kAudioDevicePropertyPreferredChannelsForStereo:
            return true;
        }
        return false;

    case kObjectID_Stream_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioObjectPropertyOwnedObjects:
        case kAudioStreamPropertyIsActive:
        case kAudioStreamPropertyDirection:
        case kAudioStreamPropertyTerminalType:
        case kAudioStreamPropertyStartingChannel:
        case kAudioStreamPropertyLatency:
        case kAudioStreamPropertyVirtualFormat:
        case kAudioStreamPropertyPhysicalFormat:
        case kAudioStreamPropertyAvailableVirtualFormats:
        case kAudioStreamPropertyAvailablePhysicalFormats:
            return true;
        }
        return false;

    case kObjectID_Volume_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioObjectPropertyOwnedObjects:
        case kAudioControlPropertyScope:
        case kAudioControlPropertyElement:
        case kAudioLevelControlPropertyScalarValue:
        case kAudioLevelControlPropertyDecibelValue:
        case kAudioLevelControlPropertyDecibelRange:
        case kAudioLevelControlPropertyConvertScalarToDecibels:
        case kAudioLevelControlPropertyConvertDecibelsToScalar:
            return true;
        }
        return false;

    case kObjectID_Mute_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:
        case kAudioObjectPropertyOwner:
        case kAudioObjectPropertyOwnedObjects:
        case kAudioControlPropertyScope:
        case kAudioControlPropertyElement:
        case kAudioBooleanControlPropertyValue:
            return true;
        }
        return false;
    }
    return false;
}

// ---------- Property: IsPropertySettable -------------------------------------

static OSStatus VolumeManager_IsPropertySettable(AudioServerPlugInDriverRef,
                                                 AudioObjectID inObjectID,
                                                 pid_t,
                                                 const AudioObjectPropertyAddress* inAddress,
                                                 Boolean* outIsSettable) {
    if (!inAddress || !outIsSettable) return kAudioHardwareIllegalOperationError;
    *outIsSettable = false;
    switch (inObjectID) {
    case kObjectID_Volume_Output:
        if (inAddress->mSelector == kAudioLevelControlPropertyScalarValue ||
            inAddress->mSelector == kAudioLevelControlPropertyDecibelValue) {
            *outIsSettable = true;
        }
        break;
    case kObjectID_Mute_Output:
        if (inAddress->mSelector == kAudioBooleanControlPropertyValue) {
            *outIsSettable = true;
        }
        break;
    default:
        break;
    }
    return noErr;
}

// ---------- Property: GetPropertyDataSize ------------------------------------

static OSStatus VolumeManager_GetPropertyDataSize(AudioServerPlugInDriverRef,
                                                  AudioObjectID inObjectID,
                                                  pid_t,
                                                  const AudioObjectPropertyAddress* inAddress,
                                                  UInt32,
                                                  const void*,
                                                  UInt32* outDataSize) {
    if (!inAddress || !outDataSize) return kAudioHardwareIllegalOperationError;
    *outDataSize = 0;

    switch (inObjectID) {
    case kObjectID_PlugIn:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:          *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:          *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyManufacturer:   *outDataSize = sizeof(CFStringRef); return noErr;
        case kAudioObjectPropertyOwnedObjects:   *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioPlugInPropertyDeviceList:     *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioPlugInPropertyTranslateUIDToDevice: *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioPlugInPropertyResourceBundle: *outDataSize = sizeof(CFStringRef); return noErr;
        }
        break;

    case kObjectID_Device:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:                  *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:                  *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyName:
        case kAudioObjectPropertyManufacturer:
        case kAudioDevicePropertyDeviceUID:
        case kAudioDevicePropertyModelUID:               *outDataSize = sizeof(CFStringRef); return noErr;
        case kAudioDevicePropertyTransportType:
        case kAudioDevicePropertyClockDomain:
        case kAudioDevicePropertyDeviceIsAlive:
        case kAudioDevicePropertyDeviceIsRunning:
        case kAudioDevicePropertyDeviceCanBeDefaultDevice:
        case kAudioDevicePropertyDeviceCanBeDefaultSystemDevice:
        case kAudioDevicePropertyLatency:
        case kAudioDevicePropertySafetyOffset:
        case kAudioDevicePropertyIsHidden:
        case kAudioDevicePropertyZeroTimeStampPeriod:    *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyNominalSampleRate:      *outDataSize = sizeof(Float64); return noErr;
        case kAudioObjectPropertyOwnedObjects:           *outDataSize = 3 * sizeof(AudioObjectID); return noErr;
        case kAudioDevicePropertyStreams:                *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyControlList:            *outDataSize = 2 * sizeof(AudioObjectID); return noErr;
        case kAudioDevicePropertyRelatedDevices:         *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioDevicePropertyAvailableNominalSampleRates: *outDataSize = sizeof(AudioValueRange); return noErr;
        case kAudioDevicePropertyPreferredChannelsForStereo:  *outDataSize = 2 * sizeof(UInt32); return noErr;
        }
        break;

    case kObjectID_Stream_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:                       *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:                       *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyOwnedObjects:                *outDataSize = 0; return noErr;
        case kAudioStreamPropertyIsActive:
        case kAudioStreamPropertyDirection:
        case kAudioStreamPropertyTerminalType:
        case kAudioStreamPropertyStartingChannel:
        case kAudioStreamPropertyLatency:                     *outDataSize = sizeof(UInt32); return noErr;
        case kAudioStreamPropertyVirtualFormat:
        case kAudioStreamPropertyPhysicalFormat:              *outDataSize = sizeof(AudioStreamBasicDescription); return noErr;
        case kAudioStreamPropertyAvailableVirtualFormats:
        case kAudioStreamPropertyAvailablePhysicalFormats:    *outDataSize = sizeof(AudioStreamRangedDescription); return noErr;
        }
        break;

    case kObjectID_Volume_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:                          *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:                          *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyOwnedObjects:                   *outDataSize = 0; return noErr;
        case kAudioControlPropertyScope:                         *outDataSize = sizeof(AudioObjectPropertyScope); return noErr;
        case kAudioControlPropertyElement:                       *outDataSize = sizeof(AudioObjectPropertyElement); return noErr;
        case kAudioLevelControlPropertyScalarValue:
        case kAudioLevelControlPropertyDecibelValue:
        case kAudioLevelControlPropertyConvertScalarToDecibels:
        case kAudioLevelControlPropertyConvertDecibelsToScalar:  *outDataSize = sizeof(Float32); return noErr;
        case kAudioLevelControlPropertyDecibelRange:             *outDataSize = sizeof(AudioValueRange); return noErr;
        }
        break;

    case kObjectID_Mute_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
        case kAudioObjectPropertyClass:            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:            *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyOwnedObjects:     *outDataSize = 0; return noErr;
        case kAudioControlPropertyScope:           *outDataSize = sizeof(AudioObjectPropertyScope); return noErr;
        case kAudioControlPropertyElement:         *outDataSize = sizeof(AudioObjectPropertyElement); return noErr;
        case kAudioBooleanControlPropertyValue:    *outDataSize = sizeof(UInt32); return noErr;
        }
        break;
    }
    return kAudioHardwareUnknownPropertyError;
}

// ---------- Property: GetPropertyData ----------------------------------------

static OSStatus VolumeManager_GetPropertyData(AudioServerPlugInDriverRef,
                                              AudioObjectID inObjectID,
                                              pid_t,
                                              const AudioObjectPropertyAddress* inAddress,
                                              UInt32,
                                              const void*,
                                              UInt32 inDataSize,
                                              UInt32* outDataSize,
                                              void* outData) {
    if (!inAddress || !outData || !outDataSize) return kAudioHardwareIllegalOperationError;

    switch (inObjectID) {

    // ===== PlugIn object =====
    case kObjectID_PlugIn:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
            if (inDataSize < sizeof(AudioClassID)) return kAudioHardwareBadPropertySizeError;
            *(AudioClassID*)outData = kAudioObjectClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyClass:
            if (inDataSize < sizeof(AudioClassID)) return kAudioHardwareBadPropertySizeError;
            *(AudioClassID*)outData = kAudioPlugInClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:
            if (inDataSize < sizeof(AudioObjectID)) return kAudioHardwareBadPropertySizeError;
            *(AudioObjectID*)outData = kAudioObjectUnknown;
            *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyManufacturer:
            return WriteString(outData, inDataSize, outDataSize,
                               CFSTR(kPlugIn_Manufacturer));
        case kAudioObjectPropertyOwnedObjects:
        case kAudioPlugInPropertyDeviceList: {
            UInt32 n = inDataSize / sizeof(AudioObjectID);
            if (n >= 1) {
                ((AudioObjectID*)outData)[0] = kObjectID_Device;
                *outDataSize = sizeof(AudioObjectID);
            } else {
                *outDataSize = 0;
            }
            return noErr;
        }
        case kAudioPlugInPropertyTranslateUIDToDevice:
            if (inDataSize < sizeof(AudioObjectID)) return kAudioHardwareBadPropertySizeError;
            *(AudioObjectID*)outData = kObjectID_Device;
            *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioPlugInPropertyResourceBundle:
            return WriteString(outData, inDataSize, outDataSize, CFSTR(""));
        }
        break;

    // ===== Device object =====
    case kObjectID_Device:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
            *(AudioClassID*)outData = kAudioObjectClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyClass:
            *(AudioClassID*)outData = kAudioDeviceClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:
            *(AudioObjectID*)outData = kObjectID_PlugIn;
            *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyName:
            return WriteString(outData, inDataSize, outDataSize, CFSTR(kDevice_Name));
        case kAudioObjectPropertyManufacturer:
            return WriteString(outData, inDataSize, outDataSize, CFSTR(kDevice_Manufacturer));
        case kAudioDevicePropertyDeviceUID:
            return WriteString(outData, inDataSize, outDataSize, CFSTR(kDevice_UID));
        case kAudioDevicePropertyModelUID:
            return WriteString(outData, inDataSize, outDataSize, CFSTR(kDevice_ModelUID));
        case kAudioDevicePropertyTransportType:
            *(UInt32*)outData = kAudioDeviceTransportTypeVirtual;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyClockDomain:
            *(UInt32*)outData = 0;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyDeviceIsAlive:
            *(UInt32*)outData = 1;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyDeviceIsRunning:
            *(UInt32*)outData = (gIOIsRunning > 0) ? 1 : 0;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyDeviceCanBeDefaultDevice:
            *(UInt32*)outData = 1;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyDeviceCanBeDefaultSystemDevice:
            *(UInt32*)outData = 1;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyLatency:
            *(UInt32*)outData = 0;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertySafetyOffset:
            *(UInt32*)outData = 0;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyIsHidden:
            *(UInt32*)outData = 0;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyZeroTimeStampPeriod:
            *(UInt32*)outData = kDevice_RingBufferSize;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioDevicePropertyNominalSampleRate:
            *(Float64*)outData = gSampleRate;
            *outDataSize = sizeof(Float64); return noErr;
        case kAudioDevicePropertyAvailableNominalSampleRates: {
            UInt32 n = inDataSize / sizeof(AudioValueRange);
            if (n >= 1) {
                AudioValueRange* r = (AudioValueRange*)outData;
                r[0].mMinimum = kDevice_SampleRate;
                r[0].mMaximum = kDevice_SampleRate;
                *outDataSize = sizeof(AudioValueRange);
            } else {
                *outDataSize = 0;
            }
            return noErr;
        }
        case kAudioObjectPropertyOwnedObjects: {
            UInt32 n = inDataSize / sizeof(AudioObjectID);
            AudioObjectID* ids = (AudioObjectID*)outData;
            UInt32 written = 0;
            AudioObjectID all[3] = { kObjectID_Stream_Output,
                                     kObjectID_Volume_Output,
                                     kObjectID_Mute_Output };
            for (UInt32 i = 0; i < 3 && i < n; ++i) { ids[written++] = all[i]; }
            *outDataSize = written * sizeof(AudioObjectID);
            return noErr;
        }
        case kAudioDevicePropertyStreams: {
            UInt32 n = inDataSize / sizeof(AudioObjectID);
            if (n >= 1) {
                ((AudioObjectID*)outData)[0] = kObjectID_Stream_Output;
                *outDataSize = sizeof(AudioObjectID);
            } else {
                *outDataSize = 0;
            }
            return noErr;
        }
        case kAudioObjectPropertyControlList: {
            UInt32 n = inDataSize / sizeof(AudioObjectID);
            UInt32 written = 0;
            AudioObjectID ctrls[2] = { kObjectID_Volume_Output, kObjectID_Mute_Output };
            AudioObjectID* out = (AudioObjectID*)outData;
            for (UInt32 i = 0; i < 2 && i < n; ++i) out[written++] = ctrls[i];
            *outDataSize = written * sizeof(AudioObjectID);
            return noErr;
        }
        case kAudioDevicePropertyRelatedDevices: {
            UInt32 n = inDataSize / sizeof(AudioObjectID);
            if (n >= 1) {
                ((AudioObjectID*)outData)[0] = kObjectID_Device;
                *outDataSize = sizeof(AudioObjectID);
            } else {
                *outDataSize = 0;
            }
            return noErr;
        }
        case kAudioDevicePropertyPreferredChannelsForStereo: {
            if (inDataSize < 2 * sizeof(UInt32)) return kAudioHardwareBadPropertySizeError;
            ((UInt32*)outData)[0] = 1;
            ((UInt32*)outData)[1] = 2;
            *outDataSize = 2 * sizeof(UInt32);
            return noErr;
        }
        }
        break;

    // ===== Stream object =====
    case kObjectID_Stream_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
            *(AudioClassID*)outData = kAudioObjectClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyClass:
            *(AudioClassID*)outData = kAudioStreamClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:
            *(AudioObjectID*)outData = kObjectID_Device;
            *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyOwnedObjects:
            *outDataSize = 0; return noErr;
        case kAudioStreamPropertyIsActive:
            *(UInt32*)outData = 1;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioStreamPropertyDirection:
            *(UInt32*)outData = 0; // 0 = output
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioStreamPropertyTerminalType:
            *(UInt32*)outData = kAudioStreamTerminalTypeSpeaker;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioStreamPropertyStartingChannel:
            *(UInt32*)outData = 1;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioStreamPropertyLatency:
            *(UInt32*)outData = 0;
            *outDataSize = sizeof(UInt32); return noErr;
        case kAudioStreamPropertyVirtualFormat:
        case kAudioStreamPropertyPhysicalFormat: {
            if (inDataSize < sizeof(AudioStreamBasicDescription))
                return kAudioHardwareBadPropertySizeError;
            AudioStreamBasicDescription* f = (AudioStreamBasicDescription*)outData;
            f->mSampleRate       = gSampleRate;
            f->mFormatID         = kAudioFormatLinearPCM;
            f->mFormatFlags      = kAudioFormatFlagIsFloat |
                                   kAudioFormatFlagsNativeEndian |
                                   kAudioFormatFlagIsPacked;
            f->mBytesPerPacket   = kDevice_NumChannels * sizeof(Float32);
            f->mFramesPerPacket  = 1;
            f->mBytesPerFrame    = kDevice_NumChannels * sizeof(Float32);
            f->mChannelsPerFrame = kDevice_NumChannels;
            f->mBitsPerChannel   = 32;
            f->mReserved         = 0;
            *outDataSize = sizeof(AudioStreamBasicDescription);
            return noErr;
        }
        case kAudioStreamPropertyAvailableVirtualFormats:
        case kAudioStreamPropertyAvailablePhysicalFormats: {
            if (inDataSize < sizeof(AudioStreamRangedDescription))
                return kAudioHardwareBadPropertySizeError;
            AudioStreamRangedDescription* f = (AudioStreamRangedDescription*)outData;
            f->mFormat.mSampleRate       = gSampleRate;
            f->mFormat.mFormatID         = kAudioFormatLinearPCM;
            f->mFormat.mFormatFlags      = kAudioFormatFlagIsFloat |
                                           kAudioFormatFlagsNativeEndian |
                                           kAudioFormatFlagIsPacked;
            f->mFormat.mBytesPerPacket   = kDevice_NumChannels * sizeof(Float32);
            f->mFormat.mFramesPerPacket  = 1;
            f->mFormat.mBytesPerFrame    = kDevice_NumChannels * sizeof(Float32);
            f->mFormat.mChannelsPerFrame = kDevice_NumChannels;
            f->mFormat.mBitsPerChannel   = 32;
            f->mFormat.mReserved         = 0;
            f->mSampleRateRange.mMinimum = kDevice_SampleRate;
            f->mSampleRateRange.mMaximum = kDevice_SampleRate;
            *outDataSize = sizeof(AudioStreamRangedDescription);
            return noErr;
        }
        }
        break;

    // ===== Volume control =====
    case kObjectID_Volume_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
            *(AudioClassID*)outData = kAudioLevelControlClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyClass:
            *(AudioClassID*)outData = kAudioVolumeControlClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:
            *(AudioObjectID*)outData = kObjectID_Device;
            *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyOwnedObjects:
            *outDataSize = 0; return noErr;
        case kAudioControlPropertyScope:
            *(AudioObjectPropertyScope*)outData = kAudioObjectPropertyScopeOutput;
            *outDataSize = sizeof(AudioObjectPropertyScope); return noErr;
        case kAudioControlPropertyElement:
            *(AudioObjectPropertyElement*)outData = kAudioObjectPropertyElementMain;
            *outDataSize = sizeof(AudioObjectPropertyElement); return noErr;
        case kAudioLevelControlPropertyScalarValue:
            pthread_mutex_lock(&gStateLock);
            *(Float32*)outData = gMasterVolume;
            pthread_mutex_unlock(&gStateLock);
            *outDataSize = sizeof(Float32); return noErr;
        case kAudioLevelControlPropertyDecibelValue: {
            pthread_mutex_lock(&gStateLock);
            Float32 v = gMasterVolume;
            pthread_mutex_unlock(&gStateLock);
            if (v <= 0.0f) v = -96.0f;
            else           v = 20.0f * log10f(v);
            *(Float32*)outData = v;
            *outDataSize = sizeof(Float32); return noErr;
        }
        case kAudioLevelControlPropertyDecibelRange: {
            AudioValueRange* r = (AudioValueRange*)outData;
            r->mMinimum = -96.0;
            r->mMaximum = 0.0;
            *outDataSize = sizeof(AudioValueRange); return noErr;
        }
        case kAudioLevelControlPropertyConvertScalarToDecibels: {
            Float32 v = *(Float32*)outData;
            if (v <= 0.0f) v = -96.0f;
            else           v = 20.0f * log10f(v);
            *(Float32*)outData = v;
            *outDataSize = sizeof(Float32); return noErr;
        }
        case kAudioLevelControlPropertyConvertDecibelsToScalar: {
            Float32 v = *(Float32*)outData;
            v = powf(10.0f, v / 20.0f);
            if (v < 0.0f) v = 0.0f;
            if (v > 1.0f) v = 1.0f;
            *(Float32*)outData = v;
            *outDataSize = sizeof(Float32); return noErr;
        }
        }
        break;

    // ===== Mute control =====
    case kObjectID_Mute_Output:
        switch (inAddress->mSelector) {
        case kAudioObjectPropertyBaseClass:
            *(AudioClassID*)outData = kAudioBooleanControlClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyClass:
            *(AudioClassID*)outData = kAudioMuteControlClassID;
            *outDataSize = sizeof(AudioClassID); return noErr;
        case kAudioObjectPropertyOwner:
            *(AudioObjectID*)outData = kObjectID_Device;
            *outDataSize = sizeof(AudioObjectID); return noErr;
        case kAudioObjectPropertyOwnedObjects:
            *outDataSize = 0; return noErr;
        case kAudioControlPropertyScope:
            *(AudioObjectPropertyScope*)outData = kAudioObjectPropertyScopeOutput;
            *outDataSize = sizeof(AudioObjectPropertyScope); return noErr;
        case kAudioControlPropertyElement:
            *(AudioObjectPropertyElement*)outData = kAudioObjectPropertyElementMain;
            *outDataSize = sizeof(AudioObjectPropertyElement); return noErr;
        case kAudioBooleanControlPropertyValue:
            pthread_mutex_lock(&gStateLock);
            *(UInt32*)outData = gMasterMute ? 1 : 0;
            pthread_mutex_unlock(&gStateLock);
            *outDataSize = sizeof(UInt32); return noErr;
        }
        break;
    }
    return kAudioHardwareUnknownPropertyError;
}

// ---------- Property: SetPropertyData ----------------------------------------

static OSStatus VolumeManager_SetPropertyData(AudioServerPlugInDriverRef,
                                              AudioObjectID inObjectID,
                                              pid_t,
                                              const AudioObjectPropertyAddress* inAddress,
                                              UInt32,
                                              const void*,
                                              UInt32 inDataSize,
                                              const void* inData) {
    if (!inAddress || !inData) return kAudioHardwareIllegalOperationError;

    switch (inObjectID) {
    case kObjectID_Volume_Output:
        if (inAddress->mSelector == kAudioLevelControlPropertyScalarValue) {
            if (inDataSize < sizeof(Float32)) return kAudioHardwareBadPropertySizeError;
            Float32 v = *(const Float32*)inData;
            if (v < 0.0f) v = 0.0f;
            if (v > 1.0f) v = 1.0f;
            pthread_mutex_lock(&gStateLock);
            gMasterVolume = v;
            pthread_mutex_unlock(&gStateLock);
            return noErr;
        }
        if (inAddress->mSelector == kAudioLevelControlPropertyDecibelValue) {
            if (inDataSize < sizeof(Float32)) return kAudioHardwareBadPropertySizeError;
            Float32 db = *(const Float32*)inData;
            Float32 v = powf(10.0f, db / 20.0f);
            if (v < 0.0f) v = 0.0f;
            if (v > 1.0f) v = 1.0f;
            pthread_mutex_lock(&gStateLock);
            gMasterVolume = v;
            pthread_mutex_unlock(&gStateLock);
            return noErr;
        }
        break;

    case kObjectID_Mute_Output:
        if (inAddress->mSelector == kAudioBooleanControlPropertyValue) {
            if (inDataSize < sizeof(UInt32)) return kAudioHardwareBadPropertySizeError;
            pthread_mutex_lock(&gStateLock);
            gMasterMute = (*(const UInt32*)inData != 0);
            pthread_mutex_unlock(&gStateLock);
            return noErr;
        }
        break;
    }
    return kAudioHardwareUnknownPropertyError;
}

// ---------- IO ----------------------------------------------------------------

static OSStatus VolumeManager_StartIO(AudioServerPlugInDriverRef, AudioObjectID, UInt32) {
    pthread_mutex_lock(&gStateLock);
    if (gIOIsRunning == 0) {
        gNumberTimeStamps = 0;
        gAnchorHostTime   = (Float64)mach_absolute_time();
    }
    ++gIOIsRunning;
    pthread_mutex_unlock(&gStateLock);
    return noErr;
}
static OSStatus VolumeManager_StopIO(AudioServerPlugInDriverRef, AudioObjectID, UInt32) {
    pthread_mutex_lock(&gStateLock);
    if (gIOIsRunning > 0) --gIOIsRunning;
    pthread_mutex_unlock(&gStateLock);
    return noErr;
}

static OSStatus VolumeManager_GetZeroTimeStamp(AudioServerPlugInDriverRef,
                                               AudioObjectID, UInt32,
                                               Float64* outSampleTime,
                                               UInt64*  outHostTime,
                                               UInt64*  outSeed) {
    pthread_mutex_lock(&gStateLock);
    UInt64 currentHost    = mach_absolute_time();
    UInt64 hostPerBuffer  = gHostTicksPerFrame * kDevice_RingBufferSize;
    UInt64 nextTsTime     = (UInt64)gAnchorHostTime + (gNumberTimeStamps * hostPerBuffer);

    if (currentHost >= nextTsTime + hostPerBuffer) {
        ++gNumberTimeStamps;
    }
    *outSampleTime = (Float64)(gNumberTimeStamps * kDevice_RingBufferSize);
    *outHostTime   = (UInt64)gAnchorHostTime + (gNumberTimeStamps * hostPerBuffer);
    *outSeed       = 1;
    pthread_mutex_unlock(&gStateLock);
    return noErr;
}

static OSStatus VolumeManager_WillDoIOOperation(AudioServerPlugInDriverRef,
                                                AudioObjectID, UInt32,
                                                UInt32 inOperationID,
                                                Boolean* outWillDo,
                                                Boolean* outWillDoInPlace) {
    bool doOp = (inOperationID == kAudioServerPlugInIOOperationWriteMix);
    if (outWillDo)        *outWillDo        = doOp;
    if (outWillDoInPlace) *outWillDoInPlace = true;
    return noErr;
}
static OSStatus VolumeManager_BeginIOOperation(AudioServerPlugInDriverRef,
                                               AudioObjectID, UInt32, UInt32, UInt32,
                                               const AudioServerPlugInIOCycleInfo*) {
    return noErr;
}
static OSStatus VolumeManager_DoIOOperation(AudioServerPlugInDriverRef,
                                            AudioObjectID, AudioObjectID, UInt32,
                                            UInt32 inOperationID,
                                            UInt32 inIOBufferFrameSize,
                                            const AudioServerPlugInIOCycleInfo*,
                                            void* ioMainBuffer, void*) {
    if (inOperationID != kAudioServerPlugInIOOperationWriteMix) return noErr;
    if (!ioMainBuffer) return noErr;

    // Apply master gain/mute. Per-client gain comes in Phase 3 once
    // AddDeviceClient is wired to the XPC helper.
    pthread_mutex_lock(&gStateLock);
    Float32 gain = gMasterMute ? 0.0f : gMasterVolume;
    pthread_mutex_unlock(&gStateLock);

    if (gain != 1.0f) {
        Float32* samples = (Float32*)ioMainBuffer;
        UInt32 n = inIOBufferFrameSize * kDevice_NumChannels;
        for (UInt32 i = 0; i < n; ++i) samples[i] *= gain;
    }
    return noErr;
}
static OSStatus VolumeManager_EndIOOperation(AudioServerPlugInDriverRef,
                                             AudioObjectID, UInt32, UInt32, UInt32,
                                             const AudioServerPlugInIOCycleInfo*) {
    return noErr;
}

// ---------- Factory -----------------------------------------------------------

extern "C" __attribute__((visibility("default")))
void* VolumeManagerPlugIn_Create(CFAllocatorRef /*allocator*/, CFUUIDRef requestedTypeUUID) {
    if (!requestedTypeUUID) return nullptr;
    // Audio Server Plug-in type UUID (fixed by Apple).
    CFUUIDRef pluginType = CFUUIDCreateFromString(nullptr,
        CFSTR("443ABAB8-E7B3-491A-B985-BEB9187030DB"));
    Boolean isASP = pluginType && CFEqual(requestedTypeUUID, pluginType);
    if (pluginType) CFRelease(pluginType);
    if (!isASP) return nullptr;

    gDriverInterface._reserved                        = nullptr;
    gDriverInterface.QueryInterface                   = VolumeManager_QueryInterface;
    gDriverInterface.AddRef                           = VolumeManager_AddRef;
    gDriverInterface.Release                          = VolumeManager_Release;
    gDriverInterface.Initialize                       = VolumeManager_Initialize;
    gDriverInterface.CreateDevice                     = VolumeManager_CreateDevice;
    gDriverInterface.DestroyDevice                    = VolumeManager_DestroyDevice;
    gDriverInterface.AddDeviceClient                  = VolumeManager_AddDeviceClient;
    gDriverInterface.RemoveDeviceClient               = VolumeManager_RemoveDeviceClient;
    gDriverInterface.PerformDeviceConfigurationChange = VolumeManager_PerformDeviceConfigurationChange;
    gDriverInterface.AbortDeviceConfigurationChange   = VolumeManager_AbortDeviceConfigurationChange;
    gDriverInterface.HasProperty                      = VolumeManager_HasProperty;
    gDriverInterface.IsPropertySettable               = VolumeManager_IsPropertySettable;
    gDriverInterface.GetPropertyDataSize              = VolumeManager_GetPropertyDataSize;
    gDriverInterface.GetPropertyData                  = VolumeManager_GetPropertyData;
    gDriverInterface.SetPropertyData                  = VolumeManager_SetPropertyData;
    gDriverInterface.StartIO                          = VolumeManager_StartIO;
    gDriverInterface.StopIO                           = VolumeManager_StopIO;
    gDriverInterface.GetZeroTimeStamp                 = VolumeManager_GetZeroTimeStamp;
    gDriverInterface.WillDoIOOperation                = VolumeManager_WillDoIOOperation;
    gDriverInterface.BeginIOOperation                 = VolumeManager_BeginIOOperation;
    gDriverInterface.DoIOOperation                    = VolumeManager_DoIOOperation;
    gDriverInterface.EndIOOperation                   = VolumeManager_EndIOOperation;

    os_log(gLog, "VolumeManagerDevice: factory returning driver ref");
    // gDriverRef is already double pointer. Do NOT take its address.
    return gDriverRef;
}
