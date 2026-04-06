package com.shin.volumemanager.audio

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.image.BufferedImage

object IconExtractor {

    private interface Shell32Ex : Library {
        fun ExtractIconExW(
            lpszFile: WString,
            nIconIndex: Int,
            phiconLarge: PointerByReference?,
            phiconSmall: PointerByReference?,
            nIcons: Int
        ): Int

        companion object {
            val INSTANCE: Shell32Ex = Native.load("shell32", Shell32Ex::class.java)
        }
    }

    private interface Kernel32Ex : Library {
        fun QueryFullProcessImageNameW(
            hProcess: WinNT.HANDLE,
            dwFlags: Int,
            lpExeName: Pointer,
            lpdwSize: IntByReference
        ): Boolean

        companion object {
            val INSTANCE: Kernel32Ex = Native.load("kernel32", Kernel32Ex::class.java)
        }
    }

    // Custom structures to avoid NativeLong type issues with JNA's WinGDI types
    @Structure.FieldOrder(
        "bmType", "bmWidth", "bmHeight", "bmWidthBytes",
        "bmPlanes", "bmBitsPixel", "bmBits"
    )
    class BITMAP : Structure() {
        @JvmField var bmType: Int = 0
        @JvmField var bmWidth: Int = 0
        @JvmField var bmHeight: Int = 0
        @JvmField var bmWidthBytes: Int = 0
        @JvmField var bmPlanes: Short = 0
        @JvmField var bmBitsPixel: Short = 0
        @JvmField var bmBits: Pointer? = null
    }

    @Structure.FieldOrder(
        "biSize", "biWidth", "biHeight", "biPlanes", "biBitCount",
        "biCompression", "biSizeImage", "biXPelsPerMeter", "biYPelsPerMeter",
        "biClrUsed", "biClrImportant"
    )
    class BITMAPINFOHEADER : Structure() {
        @JvmField var biSize: Int = 0
        @JvmField var biWidth: Int = 0
        @JvmField var biHeight: Int = 0
        @JvmField var biPlanes: Short = 0
        @JvmField var biBitCount: Short = 0
        @JvmField var biCompression: Int = 0
        @JvmField var biSizeImage: Int = 0
        @JvmField var biXPelsPerMeter: Int = 0
        @JvmField var biYPelsPerMeter: Int = 0
        @JvmField var biClrUsed: Int = 0
        @JvmField var biClrImportant: Int = 0
    }

    @Structure.FieldOrder("bmiHeader")
    class BITMAPINFO : Structure() {
        @JvmField var bmiHeader: BITMAPINFOHEADER = BITMAPINFOHEADER()
    }

    private interface GDI32Ex : Library {
        fun GetDIBits(
            hdc: WinDef.HDC, hbm: WinDef.HBITMAP,
            start: Int, cLines: Int,
            lpvBits: Pointer, lpbmi: Pointer, usage: Int
        ): Int

        companion object {
            val INSTANCE: GDI32Ex = Native.load("gdi32", GDI32Ex::class.java)
        }
    }

    fun getProcessExePath(pid: Int): String? {
        if (pid <= 0) return null
        val hProcess = Kernel32.INSTANCE.OpenProcess(
            WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid
        ) ?: return null

        return try {
            val buffer = Memory(1024L * 2)
            val size = IntByReference(1024)
            if (Kernel32Ex.INSTANCE.QueryFullProcessImageNameW(hProcess, 0, buffer, size)) {
                buffer.getWideString(0)
            } else null
        } finally {
            Kernel32.INSTANCE.CloseHandle(hProcess)
        }
    }

    fun extractIcon(exePath: String): BufferedImage? {
        val pLargeIcon = PointerByReference()
        val count = Shell32Ex.INSTANCE.ExtractIconExW(WString(exePath), 0, pLargeIcon, null, 1)
        if (count == 0 || pLargeIcon.value == null || Pointer.nativeValue(pLargeIcon.value) == 0L) {
            return null
        }

        val hIcon = WinDef.HICON(pLargeIcon.value)
        return try {
            hIconToBufferedImage(hIcon)
        } finally {
            User32.INSTANCE.DestroyIcon(hIcon)
        }
    }

    private fun hIconToBufferedImage(hIcon: WinDef.HICON): BufferedImage? {
        val iconInfo = WinGDI.ICONINFO()
        if (!User32.INSTANCE.GetIconInfo(hIcon, iconInfo)) return null

        try {
            if (iconInfo.hbmColor == null) return null

            val bmp = BITMAP()
            GDI32.INSTANCE.GetObject(iconInfo.hbmColor, bmp.size(), bmp.getPointer())
            bmp.read()

            val width = bmp.bmWidth
            val height = bmp.bmHeight
            if (width <= 0 || height <= 0) return null

            val hdc = User32.INSTANCE.GetDC(null)
            val bi = BITMAPINFO()
            bi.bmiHeader.biSize = bi.bmiHeader.size()
            bi.bmiHeader.biWidth = width
            bi.bmiHeader.biHeight = height // positive = bottom-up DIB
            bi.bmiHeader.biPlanes = 1
            bi.bmiHeader.biBitCount = 32
            bi.bmiHeader.biCompression = 0 // BI_RGB

            val bufferSize = width * height * 4
            val pixels = Memory(bufferSize.toLong())

            val compatDc = GDI32.INSTANCE.CreateCompatibleDC(hdc)
            GDI32.INSTANCE.SelectObject(compatDc, iconInfo.hbmColor)
            bi.write()
            GDI32Ex.INSTANCE.GetDIBits(compatDc, iconInfo.hbmColor, 0, height, pixels, bi.getPointer(), 0)
            GDI32.INSTANCE.DeleteDC(compatDc)
            User32.INSTANCE.ReleaseDC(null, hdc)

            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val data = pixels.getByteArray(0, bufferSize)

            // Check if any pixel has per-pixel alpha
            var hasAlpha = false
            for (i in 0 until width * height) {
                if (data[i * 4 + 3].toInt() and 0xFF != 0) {
                    hasAlpha = true
                    break
                }
            }

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val srcY = height - 1 - y // flip bottom-up to top-down
                    val offset = (srcY * width + x) * 4
                    val b = data[offset].toInt() and 0xFF
                    val g = data[offset + 1].toInt() and 0xFF
                    val r = data[offset + 2].toInt() and 0xFF
                    val a = if (hasAlpha) data[offset + 3].toInt() and 0xFF else 255
                    image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
                }
            }

            return image
        } finally {
            iconInfo.hbmColor?.let { GDI32.INSTANCE.DeleteObject(it) }
            iconInfo.hbmMask?.let { GDI32.INSTANCE.DeleteObject(it) }
        }
    }
}
