# Nexus Mobile uses platform APIs and bundled ZXing; the scanner supplies consumer rules.
# The WebRTC AAR has JNI-reflected Java methods but no consumer ProGuard rules.
-keep class org.webrtc.** { *; }
