# 西西酒馆 Android

这是一个 Android WebView 壳项目，默认加载：`http://aaa.xixisillytavern.top:8002`

## 当前实现

- Android WebView 加载酒馆站点，启用 JavaScript、DOM Storage 和 Cookie。
- 使用 Android 文件选择器支持网页上传/导入文件。
- 使用系统 DownloadManager 处理网页下载。
- 聊天正文区域屏蔽双击选词、长按上下文菜单；输入框、文本框和 contenteditable 编辑区保留正常输入/粘贴。
- 监听 SillyTavern 的 `GENERATION_ENDED` 事件（如果页面暴露该事件对象），并通过 DOM 状态变化作为兼容回退，发送 Android 系统通知。
- GitHub Actions 自动编译 Debug APK 并上传为 workflow artifact。

## GitHub Actions 编译

推送到 `main` 或 `master`，或在 Actions 页面手动运行 `Build Android APK`。成功后在该次 workflow 的 Artifacts 中下载 `xixi-tavern-debug-apk`。

## 本地编译

需要 JDK 17、Android SDK 和 Gradle 8.7：

```bash
gradle assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 注意

- 当前网站使用 HTTP，因此 Manifest 开启了 cleartext traffic。若站点以后迁移到 HTTPS，应移除此配置。
- Android 13 及以上需要允许通知权限。
- 通知检测依赖酒馆页面的生成结束事件或页面停止按钮状态；由于目前构建环境无法访问该站点进行实机验证，首次接入后需要用真实生成流程测试并按站点 DOM 微调。
- WebView 页面保持运行时可以检测回复完成；若系统强制结束应用进程，WebView 无法在进程终止后继续监听网页生成。
