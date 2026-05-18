load("@rules_android//android:rules.bzl", "android_library")
load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library", "cc_import")

cc_library(
    name = "katago_jni_native",
    srcs = ["katago_bridge.cpp"],
    linkopts = ["-llog"],
    deps = [
        "@katago_repo//:katago_lib",
    ],
    includes = [
        "../../../../../external/katago_repo+/cpp",
    ],
    alwayslink = 1,
    visibility = ["//visibility:public"],
)

cc_binary(
    name = "libkatago_jni.so",
    linkshared = 1,
    deps = [":katago_jni_native"],
    visibility = ["//visibility:public"],
)

# This is a trick to make Bazel think it's a pre-built library
cc_import(
    name = "katago_jni_import",
    shared_library = ":libkatago_jni.so",
    visibility = ["//visibility:public"],
)

android_library(
    name = "katago_bridge_java",
    srcs = ["KataGoBridge.java"],
    custom_package = "com.cwave.weiqi.katago",
    deps = [
        ":katago_jni_import",
    ],
    visibility = ["//visibility:public"],
)
