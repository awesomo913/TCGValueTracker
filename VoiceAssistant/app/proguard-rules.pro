# Add project specific ProGuard rules here.
-keepattributes *Annotation*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
