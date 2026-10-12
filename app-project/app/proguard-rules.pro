# okhttp / okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Gson uses reflection over the model classes.
-keep class com.readon.reader.data.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
