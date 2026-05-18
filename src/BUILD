load("@rules_android//android:rules.bzl", "android_application", "android_binary", "android_library", "android_local_test")
load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
load("@rules_kotlin//kotlin:android.bzl", "kt_android_library", "kt_android_local_test")
load("@rules_kotlin//kotlin:core.bzl", "kt_kotlinc_options")

package(default_visibility = ["//visibility:public"])

kt_kotlinc_options(
    name = "kt_opts",
    x_skip_prerelease_check = True,
    x_suppress_version_warnings = True,
)

java_plugin(
    name = "dagger_compiler_plugin",
    generates_api = True,
    processor_class = "dagger.internal.codegen.ComponentProcessor",
    deps = ["@maven//:com_google_dagger_dagger_compiler"],
)

java_library(
    name = "dagger",
    exported_plugins = [":dagger_compiler_plugin"],
    exports = [
        "@maven//:com_google_dagger_dagger",
        "@maven//:javax_inject_javax_inject",
    ],
)

kt_android_library(
    name = "lib",
    srcs = glob(
        ["java/com/**/*.kt"],
    ) + glob(["java/com/**/*.java"], allow_empty = True),
    assets = glob(["assets/**"], allow_empty = True),
    assets_dir = "assets",
    custom_package = "com.cwave.weiqi",
    manifest = "AndroidManifest.xml",
    plugins = [
        ":dagger_compiler_plugin",
        "//:jetpack_compose_compiler_plugin",
    ],
    resource_files = glob(["res/**"], allow_empty = True),
    deps = [
        ":dagger",
        "//src/java/com/cwave/weiqi/katago:katago_bridge_java",
        "@maven//:androidx_annotation_annotation",
        "@maven//:androidx_appcompat_appcompat",
        "@maven//:androidx_arch_core_core_common",
        "@maven//:androidx_arch_core_core_runtime",
        "@maven//:androidx_camera_camera_camera2",
        "@maven//:androidx_camera_camera_core",
        "@maven//:androidx_camera_camera_lifecycle",
        "@maven//:androidx_camera_camera_view",
        "@maven//:androidx_collection_collection",
        "@maven//:androidx_activity_activity_compose",
        "@maven//:androidx_compose_foundation_foundation",
        "@maven//:androidx_compose_foundation_foundation_layout",
        "@maven//:androidx_compose_material_material",
        "@maven//:androidx_compose_material_material_icons_extended",
        "@maven//:androidx_compose_material_material_icons_extended_android",
        "@maven//:androidx_compose_runtime_runtime",
        "@maven//:androidx_compose_ui_ui",
        "@maven//:androidx_compose_ui_ui_tooling",
        "@maven//:androidx_concurrent_concurrent_futures",
        "@maven//:androidx_concurrent_concurrent_futures_ktx",
        "@maven//:androidx_core_core",
        "@maven//:androidx_core_core_ktx",
        "@maven//:androidx_core_core_splashscreen",
        "@maven//:androidx_emoji2_emoji2",
        "@maven//:androidx_emoji2_emoji2_views_helper",
        "@maven//:androidx_fragment_fragment_ktx",
        "@maven//:androidx_lifecycle_lifecycle_common",
        "@maven//:androidx_lifecycle_lifecycle_livedata",
        "@maven//:androidx_lifecycle_lifecycle_livedata_core",
        "@maven//:androidx_lifecycle_lifecycle_livedata_core_ktx",
        "@maven//:androidx_lifecycle_lifecycle_process",
        "@maven//:androidx_lifecycle_lifecycle_runtime_compose",
        "@maven//:androidx_lifecycle_lifecycle_runtime_ktx",
        "@maven//:androidx_lifecycle_lifecycle_viewmodel_compose",
        "@maven//:androidx_profileinstaller_profileinstaller",
        "@maven//:androidx_startup_startup_runtime",
        "@maven//:androidx_tracing_tracing",
        "@maven//:androidx_savedstate_savedstate",
        "@maven//:androidx_security_security_crypto",
        "@maven//:com_google_android_material_material",
        "@maven//:org_jetbrains_kotlinx_kotlinx_coroutines_android",
        "@maven//:com_google_guava_listenablefuture",
        "@maven//:com_anthropic_anthropic_java",
    ],
)

android_binary(
    name = "app",
    custom_package = "com.cwave.weiqi",
    manifest = "AndroidManifest.xml",
    manifest_values = {
        "applicationId": "com.cwave.weiqi",
    },
    nocompress_extensions = [".so"],
    deps = [
         ":lib",
         "//src/java/com/cwave/weiqi/katago:libkatago_jni.so",
    ],
)

genrule(
    name = "release",
    srcs = [
        ":app.apk",
        "keystore/release.keystore",
    ],
    outs = ["release.apk"],
    cmd = "$(location sign.sh) $(location :app.apk) $(location keystore/release.keystore) $@ $(location @androidsdk//:apksigner) $(location @androidsdk//:zipalign_binary)",
    tools = [
        "sign.sh",
        "@androidsdk//:apksigner",
        "@androidsdk//:zipalign_binary",
    ],
)

android_application(
    name = "app_bundle",
    bundle_config_file = "config.pb.json",
    manifest = "AndroidManifest.xml",
    manifest_values = {
        "applicationId": "com.cwave.weiqi",
    },
    nocompress_extensions = [".so"],
    deps = [
        ":lib",
        "//src/java/com/cwave/weiqi/katago:libkatago_jni.so",
    ],
)

genrule(
    name = "release_bundle",
    srcs = [
        ":app_bundle_unsigned.aab",
        "keystore/release.keystore",
    ],
    outs = ["release.aab"],
    cmd = "$(location sign_bundle.sh) $(location :app_bundle_unsigned.aab) $(location keystore/release.keystore) $@",
    tools = ["sign_bundle.sh"],
)
