# MediaPipe uses reflection / JNI into these packages.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn com.google.auto.value.**
# Flogger (used by MediaPipe's Graph) finds its caller by walking the stack for its own class
# names; renaming or inlining it throws "no caller found on the stack". Keep it intact.
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
