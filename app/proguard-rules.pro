# PDFBox-Android rules
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter
-keep class com.tom_roush.pdfbox.** { *; }

# Jetpack Compose rules (standard)
-keep class androidx.compose.** { *; }

# Google Tink / Error Prone annotations
-dontwarn com.google.errorprone.annotations.**
-keep class com.google.errorprone.annotations.** { *; }
