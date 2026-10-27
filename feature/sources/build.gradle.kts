// 音源 feature：KMP 双 target（android + jvm）。
// U20 全量上收：音源列表页 SourcesScreen（原 androidMain）进 commonMain——
// SAF 选目录抽为 rememberLocalFolderPicker expect/actual（androidMain=SAF，
// jvmMain=Swing），扫描经 commonMain [LibraryScanPort] 注入（androidMain 绑定
// MediaStore/WebDAV 两扫描器），haze 2.0 为 KMP 工件。
// 形态与约束同 :feature:library（android.kmp.library，不升级版本线）。
plugins {
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()

    android {
        namespace = "com.muses.player.feature.sources"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:webdav"))
            // 洛雪自定义音源：脚本存储接口 + 仓库（在线音源脚本管理页消费）
            implementation(project(":core:lxsdk"))
            // 在线搜索：各平台搜索 provider + 聚合服务（在线搜索页消费）
            implementation(project(":core:search"))
            implementation(project(":core:ui-shared"))
            // compose/miuix 经 ui-shared api 透传，此处不再重复声明
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.datastore.preferences)
            // P2a Koin（统一 4.2.0；KMP sourceSets 依赖处理器不支持 platform(BOM)，toml bundles 已收敛版本）
            implementation(libs.bundles.koin.kmp)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            // 扫描器（MediaStore/WebDAV，安卓媒体栈，AndroidLibraryScanPort 消费）
            implementation(project(":core:media"))
            // SAF 系统目录选择器（rememberLauncherForActivityResult）
            implementation(libs.androidx.activity.compose)
        }
    }
}
