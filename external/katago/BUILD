load("@rules_cc//cc:defs.bzl", "cc_library")

cc_library(
    name = "katago_lib",
    srcs = glob(
        [
            "../../KataGo/cpp/core/*.cpp",
            "../../KataGo/cpp/game/*.cpp",
            "../../KataGo/cpp/dataio/*.cpp",
            "../../KataGo/cpp/neuralnet/*.cpp",
            "../../KataGo/cpp/book/*.cpp",
            "../../KataGo/cpp/search/*.cpp",
            "../../KataGo/cpp/program/*.cpp",
            "../../KataGo/cpp/distributed/*.cpp",
            "../../KataGo/cpp/command/*.cpp",
        ],
        exclude = [
            "../../KataGo/cpp/neuralnet/cudabackend.cpp",
            "../../KataGo/cpp/neuralnet/cudautils.cpp",
            "../../KataGo/cpp/neuralnet/trtbackend.cpp",
            "../../KataGo/cpp/neuralnet/metalbackend.cpp",
            "../../KataGo/cpp/neuralnet/openclbackend.cpp",
            "../../KataGo/cpp/neuralnet/openclkernels.cpp",
            "../../KataGo/cpp/neuralnet/openclhelpers.cpp",
            "../../KataGo/cpp/neuralnet/opencltuner.cpp",
            "../../KataGo/cpp/main.cpp",
        ],
    ) + [
        "../../KataGo/cpp/neuralnet/eigenbackend.cpp",
    ],
    hdrs = glob([
        "../../KataGo/cpp/**/*.h",
        "../../KataGo/cpp/**/*.hpp",
        "../../KataGo/cpp/external/**/*.h",
        "../../KataGo/cpp/external/**/*.hpp",
    ]),
    includes = [
        "../../KataGo/cpp",
        "../../KataGo/cpp/external",
        "../../KataGo/cpp/external/tclap-1.2.5/include",
        "../../KataGo/cpp/external/filesystem-1.5.8/include",
    ],
    defines = [
        "USE_EIGEN_BACKEND",
        "NO_GIT_REVISION",
        "NO_LIBZIP",
    ],
    deps = [
        "@bazel_tools//tools/cpp:malloc",
        "@protobuf//:protobuf",
        "@zlib//:zlib",
    ],
    visibility = ["//visibility:public"],
)
