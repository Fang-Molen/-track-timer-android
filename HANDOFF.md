# 跑道计时交接 · 0.1.6-field

更新：2026-11-04（北京时间）。最新用户指示：选择信号传输，直接做可在跑道实测的App，明天测试。用户已授权开发和上传到本仓库；不要另建仓库或重复确认。

## 当前范围

两台安卓，训练30/60/100/200/400米，单人/多人，遮挡接受同帧并列。用户使用iPad网页，交付GitHub APK链接。最新为versionCode7/versionName0.1.6-field，同包名cn.tracktimer.app、同调试签名，可覆盖旧版。根README保留用户原内容。

源码在track-timer/，APK与中文步骤在deliverables/。先读RELEASE-0.1.6.md、track-timer/verification/VALIDATION-0.1.6.md和field-signal-016设计/计划，不要从零重写。

## 本版实现

- 起点StartActivity：IPv4/六位码，TCP19119，与48轮PROBE对时共用服务；READY心跳新鲜才允许发令，固定一次本机单调时间，提示音，状态回执，取消。DONE须数字提示音完整播放；已知播放中断会取消原场次。
- 终点FinishActivity：对时、120/240帧录像、真正产帧后READY；等待时约10秒HOLD刷新对时；恢复连接先刷新，过期POLL不复活READY。可关闭预览，明确模式不降档。180秒总上限包括等待；至少6秒手动结束。
- FieldReceiver：场次和双方会话绑定，重复请求读同一事件，ACK/DONE幂等；停止录像并保存后再结束对时。取消/后台/会话变化不自动恢复成正常场次。
- RaceReviewActivity：Android9+逐帧索引解码，红色终点线，滑块/±1/±10，运动员/道次，多人同帧成绩，历史可重新打开。
- RaceStore持久化场次，历史计算绑定保存的会话。原始编码PTS对应画面索引计算，绝不用接收时刻、系统日期、名义fps或MP4播放时间替代。
- 保留全部设备、普通/高速相机、音频、对时诊断和ZIP导出。field_sync保存每轮四时间戳交换，原始录像只保存在用户手机。

## 计时定义与边界

用户已改选电子发令。零点是起点按钮触发的电子事件，不是麦克风检测到的声音。提示音输出延迟未校准；所有成绩为训练信号时间，precisionVerified/acousticLatencyCalibrated均false。不能承诺相对正式电计时±0.05秒。帧间隔不是总误差。

offset为起点减终点，elapsed=finishRaw-startHost+pre.offset。发令前对时不超过30秒、区间半宽≤5ms；结束后对时同会话、半宽≤5ms、前后偏移变化≤10ms。后校准缺失、终点中断、DONE缺失等显示仅复核。相机不完整、帧数量不匹配、采样时钟未对齐时拒绝生成成绩。

原始编码PTS与文件样本数量、顺序核验是结构性关联，不证明每帧真实曝光。实际录制长达180秒、带预览高速模式、Android逐帧解码、网络覆盖和整套精度待真机测试；不要拿单元测试当作已实测。

## 已有真机证据

vivo V2324HA，Android15。0.1.4八项普通路径都约30fps；手动8ms曝光/ISO800生效但帧周期仍33.35ms。无需继续重复普通60帧设置。

0.1.5最新日志runId b68b2583-f5a0-423b-9c00-f9de9b6dd1bb：HD120_CLOCK原始119.5704fps/1148帧，HD240_CLOCK原始239.2348fps/2336帧，EOS正常，无采集错误。稀疏元数据分别285/289点与原始编码PTS直接对齐，最大残差30/694ns。MP4相对PTS最大偏差0.679/3.171ms，fileClockMappingObserved仍false。报告未附新原视频，不能由此证明像素帧独立或物理误差。0.1.6因此用原始时间+准确帧索引，不放宽原文件时钟校准标志。

旧音频仅有最大音量下候选，未完成声学延迟标定。当前不自动减去任何固定声音延迟。

## 验证与下一步

软件测试、独立复核、签名/校验和见最新verification文件；用户原始日志/录像不上传GitHub。新任务先分析用户两台手机最新field_race/field_start/field_cue/field_sync/camera报告，按raceId关联；需要画面时请求对应视频。不要混用跨App进程会话，不把校园网同SSID等同可互通。

未做：自动识别人、飞跑/行进间双端视频触发、成绩CSV、多人复杂道次管理、多起点发令、声学/光学校准、参考电计时对照。现版长时间采集同步厂商调用仍可能卡住，工作线程期限不能抢占设备驱动。强杀导致未完成文件不能恢复成正常场次。

构建：JDK21/Gradle8.11.1/Kotlin2.1.10/AGP8.9.1/SDK35，min26 target35；./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug。签名证书SHA256：52e46c1f931b96cca6273a8ceac5f4a38f7e93d8f2238c676a90cf433f83b92a。
