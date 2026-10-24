-dontobfuscate

# XW11 methods are resolved by JNI registration in libXlorie.so, not solely by Java call reachability.
# Keep the complete bridge class so R8 cannot strip native entry points used by runtime registration.
-keep class com.termux.x11.Xw11NativeSurfaceView { *; }
