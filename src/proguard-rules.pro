# R8 keep rules for the release builds (src/BUILD: proguard_specs on :app and :app_bundle).
#
# R8 shrinks, optimizes and obfuscates the app's code (Play Console: "DEX code optimization").
# Libraries bring their own rules (AAR proguard.txt files and META-INF/proguard in jars), so this
# file only covers what is specific to the app.
#
# Keep the R8 mapping file of every release that is uploaded to Play; without it, crash stack
# traces from that release cannot be read. See README.md ("Release build: R8 mapping file").

# JNI. libkatago_jni.so and libkatago_eigen_jni.so export
# Java_com_cwave_weiqi_katago_KataGoBridge_* and Java_com_cwave_weiqi_katago_KataGoBridgeEigen_*,
# which the runtime finds by class and method name, so classes with native methods keep both.
# (GameFragment also logs the bridge's class name: "ACTIVE BRIDGE CLASS: KataGoBridge".)
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Line numbers in crash reports (de-obfuscated with the mapping file).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The defaults that the Android Gradle Plugin adds to every app (proguard-android-optimize.txt);
# Bazel does not add them.
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}
