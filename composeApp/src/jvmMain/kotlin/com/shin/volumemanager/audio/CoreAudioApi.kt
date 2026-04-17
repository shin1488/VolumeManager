package com.shin.volumemanager.audio

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid.*
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT.HRESULT
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

// Audio endpoint constants
const val eRender = 0
const val eConsole = 0
const val CLSCTX_ALL = 23

// COM interface GUIDs
val CLSID_MMDeviceEnumerator = CLSID("BCDE0395-E52F-467C-8E3D-C4579291692E")
val IID_IMMDeviceEnumerator = IID("A95664D2-9614-4F35-A746-DE8DB63617E6")
val IID_IAudioSessionManager2 = IID("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F")
val IID_IAudioSessionControl2 = IID("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d")
val IID_ISimpleAudioVolume = IID("87CE5498-68D6-44E5-9215-6DA47EF883D8")

/**
 * Base COM pointer wrapper with QueryInterface helper.
 * Uses vtable-based COM calls via JNA's Unknown class.
 */
open class ComPtr(p: Pointer?) : Unknown(p) {
    fun qi(iid: GUID): Pointer? {
        val ppv = PointerByReference()
        val hr = _invokeNativeInt(0, arrayOf(this.pointer, iid, ppv))
        return if (hr >= 0) ppv.value else null
    }
}

// IMMDeviceEnumerator vtable:
// [IUnknown 0-2] [3=EnumAudioEndpoints] [4=GetDefaultAudioEndpoint] ...
class MMDeviceEnumerator(p: Pointer?) : ComPtr(p) {
    fun getDefaultAudioEndpoint(dataFlow: Int, role: Int): Pointer {
        val ppDevice = PointerByReference()
        val hr = _invokeNativeInt(4, arrayOf(this.pointer, dataFlow, role, ppDevice))
        COMUtils.checkRC(HRESULT(hr))
        return ppDevice.value
    }
}

// IMMDevice vtable:
// [IUnknown 0-2] [3=Activate] [4=OpenPropertyStore] [5=GetId] [6=GetState]
class MMDevice(p: Pointer?) : ComPtr(p) {
    fun activate(iid: GUID, dwClsCtx: Int): Pointer {
        val ppInterface = PointerByReference()
        val hr = _invokeNativeInt(3, arrayOf(this.pointer, iid, dwClsCtx, null, ppInterface))
        COMUtils.checkRC(HRESULT(hr))
        return ppInterface.value
    }
}

// IAudioSessionManager2 vtable:
// [IUnknown 0-2] [IAudioSessionManager 3-4] [5=GetSessionEnumerator] ...
class AudioSessionManager2(p: Pointer?) : ComPtr(p) {
    fun getSessionEnumerator(): Pointer {
        val ppEnum = PointerByReference()
        val hr = _invokeNativeInt(5, arrayOf(this.pointer, ppEnum))
        COMUtils.checkRC(HRESULT(hr))
        return ppEnum.value
    }
}

// IAudioSessionEnumerator vtable:
// [IUnknown 0-2] [3=GetCount] [4=GetSession]
class AudioSessionEnumerator(p: Pointer?) : ComPtr(p) {
    fun getCount(): Int {
        val count = IntByReference()
        val hr = _invokeNativeInt(3, arrayOf(this.pointer, count))
        COMUtils.checkRC(HRESULT(hr))
        return count.value
    }

    fun getSession(index: Int): Pointer {
        val ppSession = PointerByReference()
        val hr = _invokeNativeInt(4, arrayOf(this.pointer, index, ppSession))
        COMUtils.checkRC(HRESULT(hr))
        return ppSession.value
    }
}

// IAudioSessionControl vtable:
// [IUnknown 0-2] [3=GetState] [4=GetDisplayName] [5=SetDisplayName]
// [6=GetIconPath] ... [11=UnregisterAudioSessionNotification]
class AudioSessionControl(p: Pointer?) : ComPtr(p) {
    fun getState(): Int {
        val state = IntByReference()
        val hr = _invokeNativeInt(3, arrayOf(this.pointer, state))
        if (hr < 0) return 2 // treat error as expired
        return state.value
    }

    fun getDisplayName(): String? {
        val ppName = PointerByReference()
        val hr = _invokeNativeInt(4, arrayOf(this.pointer, ppName))
        if (hr < 0 || ppName.value == null) return null
        val name = ppName.value.getWideString(0)
        Ole32.INSTANCE.CoTaskMemFree(ppName.value)
        return name.ifEmpty { null }
    }
}

// IAudioSessionControl2 (extends IAudioSessionControl) vtable:
// [IAudioSessionControl 0-11] [12=GetSessionIdentifier]
// [13=GetSessionInstanceIdentifier] [14=GetProcessId] [15=IsSystemSoundsSession]
class AudioSessionControl2(p: Pointer?) : ComPtr(p) {
    fun getProcessId(): Int {
        val pid = IntByReference()
        val hr = _invokeNativeInt(14, arrayOf(this.pointer, pid))
        COMUtils.checkRC(HRESULT(hr))
        return pid.value
    }

    /**
     * Canonical way to identify the Windows system-sounds session. Relying on
     * `pid == 0` is unreliable: system notification sounds often play through
     * the explorer.exe process, which would otherwise borrow explorer's icon
     * and display name. `IsSystemSoundsSession` returns S_OK (0) when the
     * session is the system-sounds pseudo-session, S_FALSE (1) otherwise.
     */
    fun isSystemSounds(): Boolean {
        val hr = _invokeNativeInt(15, arrayOf(this.pointer))
        return hr == 0
    }
}

// ISimpleAudioVolume vtable:
// [IUnknown 0-2] [3=SetMasterVolume] [4=GetMasterVolume] [5=SetMute] [6=GetMute]
class SimpleAudioVolume(p: Pointer?) : ComPtr(p) {
    fun setMasterVolume(level: Float) {
        val hr = _invokeNativeInt(3, arrayOf(this.pointer, level, null))
        COMUtils.checkRC(HRESULT(hr))
    }

    fun getMasterVolume(): Float {
        val level = FloatByReference()
        val hr = _invokeNativeInt(4, arrayOf(this.pointer, level))
        COMUtils.checkRC(HRESULT(hr))
        return level.value
    }

    fun setMute(mute: Boolean) {
        val hr = _invokeNativeInt(5, arrayOf(this.pointer, if (mute) 1 else 0, null))
        COMUtils.checkRC(HRESULT(hr))
    }

    fun getMute(): Boolean {
        val mute = IntByReference()
        val hr = _invokeNativeInt(6, arrayOf(this.pointer, mute))
        COMUtils.checkRC(HRESULT(hr))
        return mute.value != 0
    }
}
