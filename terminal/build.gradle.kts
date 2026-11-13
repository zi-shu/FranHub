plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "me.rerere.terminal"

    // [临时261113] externalNativeBuild 已禁用: 新环境无 x86-64 运行库, cmake 无法执行
    // externalNativeBuild {
    //     cmake {
    //         path = file("src/main/cpp/CMakeLists.txt")
    //         version = "3.22.1"
    //     }
    // }

    testOptions {
        // 上游单测会碰到 android.util.Base64 等 Android 类, 沿用上游的设置让它们返回默认值
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
