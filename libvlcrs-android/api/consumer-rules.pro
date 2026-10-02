# The native engine resolves these by name through JNI: never obfuscate them.
-keep class org.videolan.libvlcrs.NativeBridge { *; }
-keep class org.videolan.libvlcrs.SurfaceTextureBridge { *; }
-keep interface org.videolan.libvlcrs.NativeCallback { *; }
-keepclassmembers class * implements org.videolan.libvlcrs.NativeCallback {
    public void onNativeEvent(long, int, int, int);
}
