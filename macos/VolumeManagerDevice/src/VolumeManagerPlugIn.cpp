// VolumeManagerPlugIn.cpp
//
// Skeleton of a macOS Audio Server Plug-in that VolumeManager will install
// as a virtual output device. At runtime coreaudiod loads this bundle,
// calls the factory, and queries the driver interface below. The driver
// then publishes one AudioObject for the device, one for each stream, and
// one per-client "volume control" object – the knobs the UI ends up
// driving.
//
// IMPORTANT: this file is intentionally a stub. It compiles in spirit (all
// required selectors are present) but every property getter returns TODO
// markers. The real implementation must:
//
//   1. Mirror Apple's `NullAudio` / `SimpleAudioDriver` samples for the
//      device / stream plumbing.
//   2. Track per-client (per-pid) volume + mute state in a ring buffer
//      shared with the XPC helper so both directions can read/write it.
//   3. Apply gain to the mixed audio in `DoIOOperation` before handing it
//      off to the real default output device.
//
// References:
//   - CoreAudio/AudioServerPlugIn.h
//   - https://developer.apple.com/library/archive/samplecode/AudioDriverExamples/
//   - https://github.com/kyleneideck/BackgroundMusic (BGMDriver)

#include <CoreAudio/AudioServerPlugIn.h>
#include <CoreFoundation/CoreFoundation.h>
#include <pthread.h>
#include <stdio.h>

// ---------- Driver-wide state -------------------------------------------------

// TODO(macos): swap this UUID with the one in Info.plist when we generate
// a real identifier. Keeping them in lock-step is the #1 mistake when
// bringing up an AudioServerPlugIn.
static const char* kPlugIn_BundleID = "com.shin.volumemanager.device";

// Single global driver interface table. coreaudiod expects the factory to
// return a pointer to a pointer to this table (AudioServerPlugInDriverRef is
// AudioServerPlugInDriverInterface**, i.e. a double pointer).
static AudioServerPlugInDriverInterface  gDriverInterface;
static AudioServerPlugInDriverInterface* gDriverPtr = &gDriverInterface;
static AudioServerPlugInDriverRef        gDriverRef = &gDriverPtr;
static AudioServerPlugInHostRef         gHost             = nullptr;
static pthread_mutex_t                  gStateLock        = PTHREAD_MUTEX_INITIALIZER;

// ---------- IUnknown plumbing -------------------------------------------------

static HRESULT VolumeManager_QueryInterface(void* inDriver, REFIID inUUID, LPVOID* outInterface) {
    (void)inDriver;
    // TODO(macos): match kAudioServerPlugInDriverInterfaceUUID and return
    //  gDriverRef; otherwise return E_NOINTERFACE.
    if (outInterface) *outInterface = nullptr;
    return E_NOINTERFACE;
}

static ULONG VolumeManager_AddRef(void* /*inDriver*/)  { return 1; }
static ULONG VolumeManager_Release(void* /*inDriver*/) { return 1; }

// ---------- Lifecycle ---------------------------------------------------------

static OSStatus VolumeManager_Initialize(AudioServerPlugInDriverRef /*inDriver*/,
                                         AudioServerPlugInHostRef   inHost) {
    gHost = inHost;
    fprintf(stderr, "[VolumeManager] plug-in initialized\n");
    // TODO(macos): allocate device/stream/control objects, load persisted
    //  volume state from prefs, notify the host of object additions.
    return noErr;
}

static OSStatus VolumeManager_CreateDevice(AudioServerPlugInDriverRef /*inDriver*/,
                                           CFDictionaryRef /*inDescription*/,
                                           const AudioServerPlugInClientInfo* /*inClientInfo*/,
                                           AudioObjectID* /*outDeviceObjectID*/) {
    // VolumeManager ships a single static device; dynamic device creation
    // is not supported.
    return kAudioHardwareUnsupportedOperationError;
}

static OSStatus VolumeManager_DestroyDevice(AudioServerPlugInDriverRef /*inDriver*/,
                                            AudioObjectID /*inDeviceObjectID*/) {
    return kAudioHardwareUnsupportedOperationError;
}

// ---------- Object / property selectors --------------------------------------
// All of the selectors below need to return real data for the driver to
// load. They are stubbed out here so the file documents the full surface
// area that has to be implemented.

static OSStatus VolumeManager_AddDeviceClient(AudioServerPlugInDriverRef,
                                              AudioObjectID,
                                              const AudioServerPlugInClientInfo*) { return noErr; }
static OSStatus VolumeManager_RemoveDeviceClient(AudioServerPlugInDriverRef,
                                                 AudioObjectID,
                                                 const AudioServerPlugInClientInfo*) { return noErr; }
static OSStatus VolumeManager_PerformDeviceConfigurationChange(AudioServerPlugInDriverRef,
                                                               AudioObjectID,
                                                               UInt64,
                                                               void*) { return noErr; }
static OSStatus VolumeManager_AbortDeviceConfigurationChange(AudioServerPlugInDriverRef,
                                                             AudioObjectID,
                                                             UInt64,
                                                             void*) { return noErr; }

static Boolean  VolumeManager_HasProperty(AudioServerPlugInDriverRef,
                                          AudioObjectID,
                                          pid_t,
                                          const AudioObjectPropertyAddress*) { return false; }
static OSStatus VolumeManager_IsPropertySettable(AudioServerPlugInDriverRef,
                                                 AudioObjectID,
                                                 pid_t,
                                                 const AudioObjectPropertyAddress*,
                                                 Boolean* outIsSettable) {
    if (outIsSettable) *outIsSettable = false;
    return noErr;
}
static OSStatus VolumeManager_GetPropertyDataSize(AudioServerPlugInDriverRef,
                                                  AudioObjectID,
                                                  pid_t,
                                                  const AudioObjectPropertyAddress*,
                                                  UInt32,
                                                  const void*,
                                                  UInt32* outDataSize) {
    if (outDataSize) *outDataSize = 0;
    return kAudioHardwareUnknownPropertyError;
}
static OSStatus VolumeManager_GetPropertyData(AudioServerPlugInDriverRef,
                                              AudioObjectID,
                                              pid_t,
                                              const AudioObjectPropertyAddress*,
                                              UInt32,
                                              const void*,
                                              UInt32,
                                              UInt32*,
                                              void*) {
    return kAudioHardwareUnknownPropertyError;
}
static OSStatus VolumeManager_SetPropertyData(AudioServerPlugInDriverRef,
                                              AudioObjectID,
                                              pid_t,
                                              const AudioObjectPropertyAddress*,
                                              UInt32,
                                              const void*,
                                              UInt32,
                                              const void*) {
    return kAudioHardwareUnknownPropertyError;
}

static OSStatus VolumeManager_StartIO(AudioServerPlugInDriverRef, AudioObjectID, UInt32) { return noErr; }
static OSStatus VolumeManager_StopIO (AudioServerPlugInDriverRef, AudioObjectID, UInt32) { return noErr; }

static OSStatus VolumeManager_GetZeroTimeStamp(AudioServerPlugInDriverRef,
                                               AudioObjectID,
                                               UInt32,
                                               Float64*,
                                               UInt64*,
                                               UInt64*) { return noErr; }
static OSStatus VolumeManager_WillDoIOOperation(AudioServerPlugInDriverRef,
                                                AudioObjectID,
                                                UInt32,
                                                UInt32,
                                                Boolean* outWillDo,
                                                Boolean* outWillDoInPlace) {
    if (outWillDo)        *outWillDo        = false;
    if (outWillDoInPlace) *outWillDoInPlace = true;
    return noErr;
}
static OSStatus VolumeManager_BeginIOOperation(AudioServerPlugInDriverRef,
                                               AudioObjectID,
                                               UInt32,
                                               UInt32,
                                               UInt32,
                                               const AudioServerPlugInIOCycleInfo*) { return noErr; }
static OSStatus VolumeManager_DoIOOperation(AudioServerPlugInDriverRef,
                                            AudioObjectID,  // inDeviceObjectID
                                            AudioObjectID,  // inStreamObjectID
                                            UInt32,         // inClientID
                                            UInt32,         // inOperationID
                                            UInt32,         // inIOBufferFrameSize
                                            const AudioServerPlugInIOCycleInfo*,
                                            void*,          // ioMainBuffer
                                            void*) {        // ioSecondaryBuffer
    // TODO(macos): apply per-client gain here before forwarding the buffer.
    return noErr;
}
static OSStatus VolumeManager_EndIOOperation(AudioServerPlugInDriverRef,
                                             AudioObjectID,
                                             UInt32,
                                             UInt32,
                                             UInt32,
                                             const AudioServerPlugInIOCycleInfo*) { return noErr; }

// ---------- Factory -----------------------------------------------------------

extern "C" void* VolumeManagerPlugIn_Create(CFAllocatorRef /*allocator*/, CFUUIDRef requestedTypeUUID) {
    CFUUIDRef pluginType = CFUUIDCreateFromString(nullptr, CFSTR("443ABAB8-E7B3-491A-B985-BEB9187030DB"));
    if (!CFEqual(requestedTypeUUID, pluginType)) {
        CFRelease(pluginType);
        return nullptr;
    }
    CFRelease(pluginType);

    gDriverInterface.QueryInterface                  = VolumeManager_QueryInterface;
    gDriverInterface.AddRef                          = VolumeManager_AddRef;
    gDriverInterface.Release                         = VolumeManager_Release;
    gDriverInterface.Initialize                      = VolumeManager_Initialize;
    gDriverInterface.CreateDevice                    = VolumeManager_CreateDevice;
    gDriverInterface.DestroyDevice                   = VolumeManager_DestroyDevice;
    gDriverInterface.AddDeviceClient                 = VolumeManager_AddDeviceClient;
    gDriverInterface.RemoveDeviceClient              = VolumeManager_RemoveDeviceClient;
    gDriverInterface.PerformDeviceConfigurationChange= VolumeManager_PerformDeviceConfigurationChange;
    gDriverInterface.AbortDeviceConfigurationChange  = VolumeManager_AbortDeviceConfigurationChange;
    gDriverInterface.HasProperty                     = VolumeManager_HasProperty;
    gDriverInterface.IsPropertySettable              = VolumeManager_IsPropertySettable;
    gDriverInterface.GetPropertyDataSize             = VolumeManager_GetPropertyDataSize;
    gDriverInterface.GetPropertyData                 = VolumeManager_GetPropertyData;
    gDriverInterface.SetPropertyData                 = VolumeManager_SetPropertyData;
    gDriverInterface.StartIO                         = VolumeManager_StartIO;
    gDriverInterface.StopIO                          = VolumeManager_StopIO;
    gDriverInterface.GetZeroTimeStamp                = VolumeManager_GetZeroTimeStamp;
    gDriverInterface.WillDoIOOperation               = VolumeManager_WillDoIOOperation;
    gDriverInterface.BeginIOOperation                = VolumeManager_BeginIOOperation;
    gDriverInterface.DoIOOperation                   = VolumeManager_DoIOOperation;
    gDriverInterface.EndIOOperation                  = VolumeManager_EndIOOperation;

    return &gDriverRef;
}
