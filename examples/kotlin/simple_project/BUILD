load(
    "@rules_kotlin//kotlin:kotlin.bzl",
    "kt_jvm_binary",
    "kt_jvm_library",
    "kt_jvm_test",
)

package(default_visibility = ["//visibility:private"])

licenses(["notice"])

kt_jvm_library(
    name = "greeting_lib",
    srcs = ["main/lib/Greeting.kt"],
)

kt_jvm_binary(
    name = "run_project",
    srcs = ["main/ProjectRunner.kt"],
    main_class = "main.ProjectRunner",
    deps = [":greeting_lib"],
)

kt_jvm_test(
    name = "test_greeting",
    srcs = ["test/lib/GreetingTest.kt"],
    test_class = "test.lib.GreetingTest",
    deps = [
        ":greeting_lib",
        "@junit//jar",
        "@truth//jar",
    ],
)
