# OkHttp pulls in optional platform integrations that are absent at runtime.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# kotlinx.serialization keeps generated serializers via reflection on the companion.
-keepclassmembers class com.voisetranslator.** {
    *** Companion;
}
-keepclasseswithmembers class com.voisetranslator.** {
    kotlinx.serialization.KSerializer serializer(...);
}
