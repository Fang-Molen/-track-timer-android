# 0.1.5-probe 验证记录

2026-11-03，最终生产源码提交 `b9a7968fd2e4627676e8c35adff18c3206ea0a7d`。JDK21 / Gradle8.11.1 / SDK35。

完整执行 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 成功。111项测试通过（core23/app88），0失败、0错误、0跳过；lint0错误、3项原有提示（LockedOrientationActivity、DiscouragedApi、UsableSpace）。详见 [测试汇总](test-summary-0.1.5.json)。

## 改动与测试

新增720p120/240时钟校准。严格按MediaCodec输出尺寸及高速固定帧率能力选择，不支持240不降档。Android13+显式SENSOR时间基准；API28默认路径不调用新API。现有MediaRecorder、音频和48次双机对时保持可用。

MediaCodec输出保留原始PTS及观测elapsedRealtime，MP4只减首帧时间。边界测试覆盖缓冲区释放、写盘异常、配置/空EOS不计为视频帧、时间戳存储上限、重复/倒退/溢出拒绝。结束要求EOS及有效样本，资源按已分配状态释放。

采样校准测试覆盖120/240稀疏元数据、错误时钟或归零PTS不可拟合通过、未知时钟、未来传感器时间、短/错误/取消/无EOS拒绝、文件帧数或PTS变化拒绝。另发现并复现了最后一个编码时间戳附近的多余元数据可能被提前结束的循环漏计：回归断言先失败，再修为预先计算全部合格元数据数量，保持一对一匹配。最终完整111项通过。

界面与顺序测试覆盖两步运行、240失败保留120、迟到/重复回调、同相机约束、相机权限拒绝重试和后台授权不启动硬件。

## 独立复核

一轮独立只读代码复核审查26dc145至b9a7968。结论：适合诊断版本交付，无Critical、Important或可执行Minor发现。复核检查了资源部分构造、编码/封装错误清理、EOS、时间门槛、文件映射和取消路径；另核验111项测试与lint证据，没有重复运行Gradle。

复核明确未替代以下实机验证：120/240支持与持续吞吐；厂商是否遵守SENSOR时间基准；MP4时基量化能否满足2微秒匹配容差；真实硬件关闭/EOS/前后台行为；逐帧独立曝光、滚动快门、声学起点与整体计时误差。这些均保留为用户本轮测试或后续光学/声学校准的验证边界，不把软件测试当成真机通过。

签名、版本、校验和由主执行者另行核对；实际覆盖安装仍待手机运行。

## APK

包名cn.tracktimer.app，versionCode6 / versionName0.1.5-probe / minSDK26 / targetSDK35。v2签名校验通过，证书与旧版相同，支持覆盖安装；记录见 [签名信息](apk-signature-0.1.5.txt)。大小1,058,643字节，SHA256：

`fd6bbd06a2f29eb362b52e5bbb0adfc74954dcde651f9c1bf1c4c4ca4126aae1`

## 使用边界

本轮先做单机内部时钟对照，现有双机对时独立保留。clockSession可关联同一App会话报告，不自动套用历史偏移或输出跑步成绩。2微秒是数字时间戳匹配容差，不能理解成物理误差。仅在采样匹配和文件PTS完整保留均通过时给fileZeroLocalElapsedNs；captureToEncodedMappingVerified、precisionVerified仍为false。

AOSP文档说明编码Surface默认可能使用MONOTONIC时间而传感器REALTIME对应elapsedRealtime，因此本版显式请求SENSOR并用观测验证。参考： https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/hardware/camera2/params/OutputConfiguration.java 。

已有V2324HA高速MediaRecorder录像约119.714fps，这不能代替新版MediaCodec路径或240帧真机验证。原始用户日志及媒体未加入仓库。已知限制仍包括：工作线程上的启动/排空期限不能抢占卡死的同步厂商调用。
