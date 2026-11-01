# 0.1.1-probe 验证记录

日期：2026-11-01。使用 JDK21 / Gradle8.11.1 / Android SDK35。

执行 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 成功。23 项核心测试、13 项 Android 框架测试，共 36 项通过。框架测试覆盖 Android API28，另在 API26 验证旧相机会话路径。

回归对照：临时恢复旧的系统提示音用途、删除提前提交的会话参数、忽略预览帧时长限制，3 项对应测试全部按预期失败；恢复修正后全套通过。新增迟到相机会话回调测试先复现线程退出后的拒绝执行，修正后通过。媒体静音的独立试听不请求麦克风权限，并能恢复按钮与导出。

APK 包名 cn.tracktimer.app，versionCode2，versionName0.1.1-probe，minSDK26，targetSDK35。apksigner 验证 v2 签名，与 0.1.0-probe 的签名证书 SHA-256 相同，可覆盖升级而保留应用数据。详细摘要见 test-summary-0.1.1.json 和 apk-signature-0.1.1.txt。

Lint 无错误；警告数量和类别见 JSON。云端 lint 分析器另有本机 analytics.settings 不可写提示，不影响构建。

这些是自动化软件检查，不能证明 vivo V2324HA 实际已经发声、达到60fps或达到0.05秒计时精度。新版还需用户真机复测；本次没有新设备 ZIP、没有可靠电计时参考对照。未上传用户原始录音录像。
