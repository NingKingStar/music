# Muses

一个用 Kotlin Multiplatform 编写的本地 / 在线音乐播放器：Android 与 Windows 桌面共用同一套业务代码与界面代码。

> ## ⚠️ 请先读免责声明
>
> - 本项目是**个人学习与技术研究性质的非官方项目**，与任何音乐平台、任何原厂软件**均无关联**，也未获其授权或背书。
> - 本项目**不托管、不存储、不销售、不再分发任何音频文件**；歌曲、封面、歌词的版权均归各自权利人。
> - 在线播放与下载依赖**使用者自行提供**或**第三方公开接口**返回的链接。请仅用于你已购买、已获授权或法律允许的内容。
> - 本项目**免费**，不含广告、内购与捐赠入口，不提供任何付费/会员内容的解锁或破解服务。
> - 权利人如认为本仓库内容侵权，请见 [通知与下架](#通知与下架)，**无需先发律师函**，核实后即会移除。
>
> 完整条款（中英双语）：**[DISCLAIMER.md](DISCLAIMER.md)**

---

## 功能

- **本地曲库**：扫描本地音乐，按歌曲 / 专辑 / 艺术家 / 文件夹浏览，支持搜索
- **歌单**：自建歌单、收藏、歌单导入（粘贴网易云 / QQ 音乐的公开歌单链接，导入前可确认名称与曲目数）
- **在线搜索**：输入即搜、可管理的本地搜索历史、结果按平台分组
- **音源脚本**：内置第三方音源脚本运行时（QuickJS），可播放 / 下载脚本解析出的直链
- **下载**：单曲下载与歌单「全部下载」，可选优先音质，实际不可用时自动降档；下载任务与已保存音乐分开管理
- **播放**：后台播放、媒体通知与蓝牙控制、播放队列与顺序 / 随机 / 单曲循环、播放失败自动恢复
- **歌词与封面**：本地与在线歌词、封面抓取，支持多来源匹配与人工审核后写回

## 技术栈

Kotlin Multiplatform · Compose Multiplatform · Media3 / ExoPlayer · Room + SQLite · Koin · OkHttp · Coil · QuickJS

## 目录结构

```
app/             Android 应用壳（打包、清单、签名）
composeApp/      桌面端应用壳（Compose Desktop，MSI / EXE）
desktop/         桌面端播放与平台实现（VLCJ 播放后端）
core/            共享核心：数据、模型、播放端口、搜索、媒体会话、音源脚本运行时
feature/         界面与交互：home / library / player / sources / shell
docs/            第三方声明与设计记录
changelog/       各版本变更记录
```

## 构建

前置：JDK 21、Android SDK 37（编译 Android 端）。

```bash
# Android（release 变体）
./gradlew :app:assembleMusesRelease

# 桌面端：直接运行 / 打包
./gradlew :composeApp:run
./gradlew :composeApp:packageMsi
```

版本号通过 `-Pandroid.injected.versionName=... -Pandroid.injected.versionCode=...` 注入；签名相关参数不入库。

## 第三方与许可

- 第三方依赖、二进制运行时（VLC）与外部接口的出处、许可与免责说明见 **[docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)**。
- `core/lxsdk/src/jvmShared/resources/lxscripts` 下的第三方音源脚本著作权归其各自作者，随目录保留上游的 Apache-2.0 许可证文件（同目录 `LICENSE`），本仓库不主张任何权利。
- 本仓库**未授予任何开源许可**：除 GitHub 服务条款所必需的范围外，作者保留全部权利。未获书面许可，不得再分发、商用或用于收费分发。

## 通知与下架

如果你是权利人，认为本仓库内容侵犯你的权益：提交标题带 `[Takedown]` 的 Issue，或邮件至 `1014088567@qq.com`，说明具体位置、权利证明与联系方式。核实后会在尽快（原则上 72 小时内）移除，不需要先行发送律师函。

本项目尊重合法权利，不以本项目获取任何利益。
