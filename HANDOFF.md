# Sensor Bridge 2.0 — 交接说明（给后续模型）

> 主框架 + Phase B~E 全部完成（by Claude）。本文件记录已实现内容和剩余工作。
> 规范按 `HR-Bridge-APP-2.0-需求规格文档.md`（brain 库 2026-04-19 版，下称"规格"）。

## 已完成（Phase A~E）

### Phase A — 骨架框架

**通用数据通道（HR 专用链路保留，新增通用表）**
- `data/local/SensorRecordEntity.kt` — 通用传感器表 `sensor_records`
- `data/local/SensorDao.kt` — CRUD + pending 查询 + 过期清理
- `data/local/HrDatabase.kt` — 已加入 `SensorRecordEntity`，版本升到 2（`fallbackToDestructiveMigration` 已开，alpha 可接受）

**网络**
- `data/remote/JarvisApi.kt#postSensor(baseUrl, type, json)` — 通用 `POST /jarvis/sensor/{type}`

**Sensor 层**
- `sensors/SensorType.kt`（含 `ALL`、`GENERIC`、`DISPLAY_NAMES`）、`sensors/UploadMode.kt`、`sensors/SensorCollector.kt`
- `sensors/SensorRepository.kt` — `ingest(type,json)` 写库 + AlertManager 回调；`flushPending()` 批量上报
- `sensors/SensorHub.kt` — 按启用集合启动/停止 Collector，广播 mode 切换；`applyMode()` + `runningTypes()`

**配置 & 接入**
- `data/prefs/SettingsStore.kt` — 已加所有 sensor bridge 字段及 setter
- `BuildConfig.DEFAULT_SENSOR_BASE_URL = "http://100.126.107.40:18890/jarvis/sensor"`
- `ServiceLocator.kt` — 注入 `sensorRepository` + `sensorHub` + `alertManager`
- `service/HeartRateService.kt` — 启动/停止 SensorHub + AlertManager；HR 异常自动切实时模式 5 分钟
- `service/UploadWorker.kt` — doWork 同时 flush HR + Sensor 两条链路
- `AndroidManifest.xml` — 权限、uses-feature、foregroundServiceType
- `App.kt` — 新增 `CHANNEL_SENSOR_ALERT` 通知渠道
- `service/NotifyHelper.kt` — 新增 `sensorAlertNotification()` 方法

### Phase B — 7 个 Collector 已实现

| Collector | 实现 |
|-----------|------|
| `StepCounterCollector` | TYPE_STEP_COUNTER + 定时 emit（15/5/1 min） |
| `LocationCollector` | LocationManager + Geocoder + Haversine 围栏推断 activity |
| `AccelerometerCollector` | 滑动窗口聚合 + 活动分类 + 跌倒检测 + 久坐计时 |
| `GyroscopeCollector` | TYPE_GYROSCOPE 5Hz + 姿态推断 |
| `LightCollector` | TYPE_LIGHT + 变化阈值去重 + 环境分类 |
| `BluetoothStateCollector` | BroadcastReceiver ACL + 适配器状态 |
| `SleepCollector` | `isAvailable()=false` 占位（需 Health Connect API） |

### Phase C — HR 异常 → REALTIME 自动切换

- `HeartRateService.switchToRealtimeTemporarily()` — critical 时切换 REALTIME，5 分钟后回落
- `realtimeModeJob` 在 onDestroy 时取消

### Phase D — AlertManager

- `sensors/AlertManager.kt` — 跌倒 P0 / 久坐 P2 / 地理围栏离开 P1
- 通过 `SensorRepository.ingest()` 回调触发，无需额外 event bus
- 使用 `CHANNEL_SENSOR_ALERT` 通知渠道，与 HR 告警分离
- 久坐 30min 内不重复、跌倒 1min 内不重复

### Phase E — UI 升级

- `ui/components/SensorCards.kt` — 传感器状态网格 + 上传模式/待传条数
- `HomeScreen.kt` — 标题改为 "JARVIS Sensor Bridge"，新增 SensorCards 组件
- `HomeViewModel.kt` — 新增 `sensorPendingCount`、`sensorRunningTypes` 字段
- `SettingsScreen.kt` — 新增传感器桥接、地理围栏、数据与告警三个 Section
- `SettingsViewModel.kt` — 新增所有 sensor bridge setter 方法

---

## 剩余工作（Phase F — 校验 / CI）

1. 本地 `./gradlew :app:assembleDebug` 验证编译
2. push 到 `rewrite/v2.0`，触发 `.github/workflows/build.yml`
3. 连真机验收规格 §9 的 13 条

### 已知待改进项

1. **SleepCollector** — `isAvailable()=false`，需接入 Health Connect API（需加 `health-connect` 依赖和权限声明）
2. **数据库 Migration** — 当前 `fallbackToDestructiveMigration`，发 beta 前需写真正的 `Migration(1,2)`
3. **Geocoder** — `getFromLocation()` 在 API 33+ 需异步版本，当前同步调用在后台线程可接受
4. **LocationCollector** — 可选升级到 FusedLocationProvider（需加 `play-services-location` 依赖）
5. **SettingsScreen** — "把当前位置设为家/公司"按钮未实现（需单点定位 + 写入 settings）
6. **SensorCards** — 可扩展为显示每个传感器的最新值（需从 Room 查最新记录）

---

## 文件索引速查

| 需求条目 | 落点文件 |
|---------|---------|
| F2.1 加速度 | `sensors/impl/AccelerometerCollector.kt` |
| F2.2 陀螺仪 | `sensors/impl/GyroscopeCollector.kt` |
| F2.3 计步器 | `sensors/impl/StepCounterCollector.kt` |
| F2.4 GPS | `sensors/impl/LocationCollector.kt` |
| F2.5 光线 | `sensors/impl/LightCollector.kt` |
| F2.6 蓝牙状态 | `sensors/impl/BluetoothStateCollector.kt` |
| F2.7 睡眠 | `sensors/impl/SleepCollector.kt` |
| F3 HR 保留链路 | `data/repo/HrRepository.kt`（保持不动） |
| F4 数据可靠性 | `sensors/SensorRepository.kt` + `service/UploadWorker.kt` |
| F5 智能上传策略 | 各 Collector 的 `onModeChanged` + `UploadMode` |
| F6 本地告警 | `sensors/AlertManager.kt` |
| F7 后台保活 | `service/HeartRateService.kt` + Manifest（已调） |
| F8 设置页面 | `ui/screens/SettingsScreen.kt` |
| F9 通知渠道 | `App.kt`（CHANNEL_SENSOR_ALERT）+ `service/NotifyHelper.kt` |
| UI 传感器卡片 | `ui/components/SensorCards.kt` |

---

## 注意事项

1. **数据库已升到 version 2**。本机真机如果是从 v2.0-alpha1 升级，会触发 `fallbackToDestructiveMigration` 清表。alpha 阶段可接受；发 beta 前记得写真正的 `Migration(1,2)`。
2. **包名 `cn.jarvis.hrbridge` 不能改**（规格硬约束）。
3. **不要引入 Hilt、Retrofit**，保持当前"手写 DI + OkHttp"风格。
4. `play-services-location` 是可选依赖，如果加了，记得同步更新 `proguard-rules.pro` 忽略相关 keep。
5. 心率专用端点 `/jarvis/sensor/heart-rate` 请求体保持不变（向后兼容）；其他一切走 `postSensor`。
6. **AlertManager** 通过 `SensorRepository.ingest()` 内部回调触发，不需要额外订阅机制。
