

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.muses.player"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // 卡拉OK 歌词渲染依赖 Compose BlurEffect（API 31+ 才生效），下限由 26 抬到 29
        minSdk = libs.versions.minSdkApp.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // CI 发布经 -Pandroid.injected.version* 注入（tag 名/提交总数）；
        // AGP 9 中 DSL 显式赋值会覆盖 injected 属性，故必须在此主动读取
        versionCode = (findProperty("android.injected.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("android.injected.versionName") as String?) ?: "0.5.1"
        val diagnosticCommit = providers.gradleProperty("diagnosticCommit").orNull
            ?.takeIf { it.matches(Regex("[a-f0-9]{7,40}")) } ?: "unknown"
        buildConfigField("String", "DIAGNOSTIC_COMMIT", "\"$diagnosticCommit\"")
    }

    // 语言资源过滤（zh-rCN/en）：AGP 9.4 趡牌 defaultConfig.resourceConfigurations，迁至 androidResources.localeFilters
    androidResources {
        localeFilters += listOf("zh-rCN", "en")
    }

    // 渠道维度：主包 com.muses.player（覆盖安装旧 Web 版）+ MIUI 定制包
    // MIUI 定制包沿用 com.miui.player：v0.6.7 曾改为 com.tencent.qqmusic 以套用 CarWith 的
    // 音乐/歌词卡片白名单，但换包名会丢旧包本地数据、且与官方 QQ 音乐互斥，故回退。
    flavorDimensions += "channel"
    productFlavors {
        create("muses") {
            dimension = "channel"
            applicationId = "com.muses.player"
        }
        create("miui") {
            dimension = "channel"
            applicationId = "com.miui.player"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// AGP 9 内置 Kotlin 支持：jvmTarget 默认取 android.compileOptions.targetCompatibility

dependencies {
    // 模块依赖：app 聚合全部 core/feature
    implementation(project(":core:common"))
    implementation(project(":feature:scrape"))
    // U24：:core:ui 空壳删除，消费方直连 :core:ui-shared
    implementation(project(":core:ui-shared"))
    implementation(project(":core:data"))
    implementation(project(":core:webdav"))
    implementation(project(":core:media"))
    // 洛雪自定义音源（在线音源）：引擎 + 脚本存储 + OnlineTrackResolver 装配
    implementation(project(":core:lxsdk"))
    // 在线搜索：5 平台 provider + 聚合服务
    implementation(project(":core:search"))
    // AI 推荐（曲库画像 → LLM → 平台匹配）：aiModule 装配需本模块可见
    implementation(project(":core:ai"))
    // P2a：AppKoinModule 直接聚合 lyrics/scrape 的 Koin 模块，需直连依赖（implementation 非传递）
    // 09-05-lyrics-kmp X3：lyricsModule 已上收 :core:common jvmShared 同包名，随 core:common 依赖可达
    implementation(project(":core:scrape"))
    implementation(project(":feature:library"))
    implementation(project(":feature:player"))
    implementation(project(":feature:sources"))
    // 首页（搜索框 + 排行榜 + 猜你喜欢）：homeCoreModule 装配
    implementation(project(":feature:home"))
    // U22：应用壳（MusesApp/TabsLayout/SettingsScreen + CMP Navigation 导航）双端共享
    implementation(project(":feature:shell"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Compose（U24：app 无自有 UI，仅 MainActivity setContent 壳需要 runtime/ui 基线；
    // MusesTheme/组件经 :core:ui-shared 与 :feature:shell 提供）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)

    // P2a Koin（BOM 统一 4.2.0，无散装版本号）
    implementation(platform(libs.koin.bom))
    implementation(libs.bundles.koin.android.set)
    implementation(libs.koin.compose.viewmodel)

    // WorkManager（ScanWorker 为 KoinComponent 懒注入，见 P2a R3）
    implementation(libs.work.runtime.ktx)

    // jaudiotagger（MusesApplication 启动期 TagOptionSingleton.setAndroid(true) 强制安卓分支）
    implementation(libs.jaudiotagger)

    testImplementation(libs.junit)
}
