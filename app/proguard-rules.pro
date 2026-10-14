# JNI entry points and the callback interface invoked from native code
-keep class app.nocturne.audio.NativeEngine { native <methods>; *; }
-keep interface app.nocturne.audio.NativeEngine$ProgressListener { *; }
-keep class * implements app.nocturne.audio.NativeEngine$ProgressListener { *; }
