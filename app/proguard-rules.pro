# ZXing QR generation
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# Settings are persisted by enum constant name (Codec/CameraLens/WhiteBalance
# are stored as strings in SharedPreferences and restored with Enum.entries
# .firstOrNull { it.name == ... }). Keep the names stable so R8 obfuscation
# can't silently reset the user's settings on release builds.
-keep enum com.opencam.Codec, com.opencam.CameraLens, com.opencam.WhiteBalance { *; }

# MediaCodec / camera are framework APIs, nothing to keep here.
