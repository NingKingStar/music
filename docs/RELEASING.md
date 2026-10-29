# 发布安装包到 GitHub

用户从仓库的 Releases 页面进入版本，在 Assets 中下载 APK。`Source code` 是源码压缩包，不是安装包。

## 当前本地签名包的发布

1. 完成相关测试，提交并推送对应源码。
2. 构建压缩的正式 APK，使用项目原签名；核对包名、版本、证书、非调试配置、ZIP 对齐并计算 SHA-256。
3. 在 GitHub 的 Releases → Draft a new release 中选择准确提交，填写标签、更新说明并上传 APK 与 `SHA256SUMS.txt`。
4. 保存草稿，核对附件内容与来源提交。草稿只对有权限的维护者可见；审核后点击 Publish release 才公开下载。
5. 后续同签名更新递增 `release.properties` 中的 `versionCode`。签名不同不能直接覆盖，不能通过降低版本检查解决。

## 后续云端构建草稿

仓库保留手动触发的 Release 工作流，避免公开草稿时新建标签又触发重复发版。

1. 更新 `release.properties` 和对应 `changelog/v版本.md`，提交并推送。
2. 将 `v版本` 标签推送到已核验的提交。
3. 在 Actions → Release → Run workflow 输入该标签。工作流检出标签内容，校验标签与正式版本一致，构建安卓渠道包并验证固定证书和对齐，生成校验文件。
4. 安卓成功即生成草稿；Windows 安装包构建成功后补充，不阻塞安卓附件。审核草稿后手动公开。

云端签名需要仓库已有的 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` Secrets；必须与项目原签名一致。发布本地签名 APK 不需要将私钥上传 GitHub。缺少这些 Secrets 时，应上传已核验的本地包，不把未签名产物作为正式安装包。

本地保留当前版和最多一个上一版；公开 GitHub 历史 Release 不受本地清理规则影响。保留正式构建映射文件，以便定位混淆后的崩溃。
