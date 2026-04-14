# Keep JNA — it links Java methods to native code via reflection + bytecode
# naming conventions, so both the class names and member signatures have to
# survive minification. Without this ProGuard happily strips or renames
# Structure fields, Library interfaces, and the vtable glue in Unknown,
# which is why the packaged MSI build could launch but never enumerate any
# audio sessions.
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    <methods>;
}
-keepattributes *Annotation*, Signature, Exceptions, InnerClasses, EnclosingMethod

# Keep our Windows COM wrappers. They extend JNA's Unknown and call
# _invokeNativeInt with hard-coded vtable slots; renaming or stripping any
# of it produces an app that runs but silently returns E_FAIL on every COM
# method.
-keep class com.shin.volumemanager.audio.** { *; }
