# ---- Vidora R8 rules ----
# Release class/member names are obfuscated by R8.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# NewPipe Extractor (uses reflection + Rhino JS engine for signature deciphering)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# jsoup / nanojson
-keep class com.grack.nanojson.** { *; }
-dontwarn org.jsoup.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.vidora.app.**$$serializer { *; }
-keepclassmembers class com.vidora.app.** { *** Companion; }
-keepclasseswithmembers class com.vidora.app.** { kotlinx.serialization.KSerializer serializer(...); }

# WorkManager workers are instantiated by reflection
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# BotGuard / poToken WebView bridge — methods are called from JavaScript
-keepclassmembers class com.vidora.app.potoken.PoTokenWebView {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# WebView JavaScript bridges (PoToken + EJS challenge solver)
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
