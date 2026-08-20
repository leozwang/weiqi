load("@rules_android//android:rules.bzl", "android_library")
load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library", "cc_import")

cc_library(
    name = "katago_jni_native",
    srcs = ["katago_bridge.cpp"],
    copts = ["-fexceptions"],
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
    linkstatic = 1,
    linkopts = [
        "-llog",
        "-static-libstdc++",
        "-static-libgcc",
        "-lunwind",
        "-Wl,-z,max-page-size=16384",
    ],
    deps = [
        ":katago_jni_native",
        "@katago_repo//:libkatago_opencl.so",
    ],
    visibility = ["//visibility:public"],
)

# This is a trick to make Bazel think it's a pre-built library
cc_import(
    name = "katago_jni_import",
    shared_library = ":libkatago_jni.so",
    visibility = ["//visibility:public"],
)

cc_import(
    name = "katago_opencl_import",
    shared_library = "@katago_repo//:libkatago_opencl.so",
    visibility = ["//visibility:public"],
)

cc_library(
    name = "katago_jni_native_eigen",
    srcs = ["katago_bridge.cpp"],
    copts = ["-fexceptions"],
    linkopts = ["-llog"],
    deps = [
        "@katago_repo//:katago_lib_eigen",
    ],
    includes = [
        "../../../../../external/katago_repo+/cpp",
    ],
    alwayslink = 1,
    visibility = ["//visibility:public"],
)

cc_binary(
    name = "libkatago_eigen_jni.so",
    linkshared = 1,
    linkstatic = 1,
    linkopts = [
        "-llog",
        "-static-libstdc++",
        "-static-libgcc",
        "-lunwind",
        "-Wl,-z,max-page-size=16384",
    ],
    deps = [
        ":katago_jni_native_eigen",
    ],
    visibility = ["//visibility:public"],
)

cc_import(
    name = "katago_eigen_jni_import",
    shared_library = ":libkatago_eigen_jni.so",
    visibility = ["//visibility:public"],
)

cc_library(
    name = "katago_jni_native_tpu",
    srcs = ["katago_bridge.cpp"],
    copts = ["-fexceptions"],
    linkopts = ["-llog"],
    deps = [
        "@katago_repo//:katago_lib_eigen",
    ],
    includes = [
        "../../../../../external/katago_repo+/cpp",
    ],
    alwayslink = 1,
    visibility = ["//visibility:public"],
)

cc_binary(
    name = "libkatago_tpu_jni.so",
    linkshared = 1,
    linkstatic = 1,
    linkopts = [
        "-llog",
        "-static-libstdc++",
        "-static-libgcc",
        "-lunwind",
        "-Wl,-z,max-page-size=16384",
    ],
    deps = [
        ":katago_jni_native_tpu",
    ],
    visibility = ["//visibility:public"],
)

cc_import(
    name = "katago_tpu_jni_import",
    shared_library = ":libkatago_tpu_jni.so",
    visibility = ["//visibility:public"],
)

android_library(
    name = "katago_bridge_java",
    srcs = [
        "IKataGoBridge.java",
        "KataGoBridge.java",
        "KataGoBridgeEigen.java",
        "KataGoBridgeTPU.java",
        "TfLiteTpuEvaluator.java",
    ],

    custom_package = "com.cwave.weiqi.katago",
    deps = [
        ":katago_jni_import",
        ":katago_opencl_import",
        ":katago_eigen_jni_import",
        ":katago_tpu_jni_import",
        "@maven//:org_tensorflow_tensorflow_lite",
        "@maven//:org_tensorflow_tensorflow_lite_api",
    ],
    visibility = ["//visibility:public"],
)

