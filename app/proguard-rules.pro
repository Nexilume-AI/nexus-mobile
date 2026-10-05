# Nexus Mobile uses platform APIs and bundled ZXing; the scanner supplies consumer rules.
# The WebRTC AAR has JNI-reflected Java methods but no consumer ProGuard rules.
-keep class org.webrtc.** { *; }
# WebRTC 150 also loads Chromium's JNI Zero bridge from JNI_OnLoad.
# There is no Java reference for R8 to follow; removing/renaming these classes
# aborts the entire process before PeerConnectionFactory.initialize can return.
# Keep native entry points, not unused Java helpers. In particular the AAR's
# unused setJniClassLoader helper references a generated JniZeroJni class which
# that AAR does not ship. Do not keep it or mask missing classes with -dontwarn.
-keep,includedescriptorclasses class org.jni_zero.** {
    @org.jni_zero.CalledByNative <methods>;
    @org.jni_zero.CalledByNativeUnchecked <methods>;
    @org.jni_zero.AccessedByNative <fields>;
}
