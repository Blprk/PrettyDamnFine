# MuPDF fitz: JNI resolves classes and fields by name at runtime (e.g. Context$Log),
# so R8 must not rename, strip or shrink any of them.
-keep class com.artifex.mupdf.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Jetpack Compose rules (standard)
-keep class androidx.compose.** { *; }

# Google Tink / Error Prone annotations
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.errorprone.annotations.** { *; }
