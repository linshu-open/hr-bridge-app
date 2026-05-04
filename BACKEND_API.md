# HR Bridge 2.0 — 后台端点规范

> 客户端：`cn.jarvis.hrbridge` v2.0.0-alpha1
> 默认服务器：`http://100.126.107.40:18890`
> 统一响应编码 UTF-8，Content-Type: `application/json`

---

## 端点总览

| 名称 | 方法 | 路径 | 用途 |
|---|---|---|---|
| HR 单条上传 | POST | `/jarvis/sensor/heart-rate` | 紧急心率立即上传、手动测试 |
| HR 批量上传 | POST | `/jarvis/sensor/heart-rate/batch` | UploadWorker 每 15min 批量推 |
| 通用传感器上传 | POST | `/jarvis/sensor/{type}` | 7 种传感器全部走这个 |

> `{type}` ∈ `step_count` / `location` / `accelerometer` / `gyroscope` / `light` / `bluetooth` / `sleep`

---

## 1. HR 单条 `POST /jarvis/sensor/heart-rate`

### Request Body
```json
{
  "hr": 78,
  "avg": 76,
  "status": "normal",
  "trend": "stable",
  "samples": 1,
  "device": "HUAWEI Band 9-AB12",
  "ts": 1734567890,
  "token": "optional-auth-token"
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `hr` | int | 瞬时心率 bpm |
| `avg` | int? | 当前窗口均值；单条可传同 hr |
| `status` | string | 见 [HrStatus 枚举](#hrstatus-枚举) |
| `trend` | string | `up` / `down` / `stable` |
| `samples` | int | 本条聚合的样本数 |
| `device` | string | 设备显示名 |
| `ts` | long | 秒级 epoch |
| `token` | string? | 可选鉴权 token |

### HrStatus 枚举
- `critical_low` — 极低危险（默认 < 45）
- `low` — 偏低（45–59）
- `normal` — 正常（60–89）
- `elevated` — 偏高（90–119）
- `critical_high` — 极高危险（≥ 120）
- `test` — 客户端测试连接专用（`testServer` 按钮）

---

## 2. HR 批量 `POST /jarvis/sensor/heart-rate/batch`

### Request Body
```json
{
  "device": "HUAWEI Band 9-AB12",
  "token": "optional",
  "count": 3,
  "samples": [
    {"hr": 75, "status": "normal", "trend": "stable", "ts": 1734567800},
    {"hr": 78, "status": "normal", "trend": "up",     "ts": 1734567860},
    {"hr": 82, "status": "normal", "trend": "up",     "ts": 1734567920}
  ]
}
```

> 若返回 **404** 客户端会自动降级到 `/heart-rate` 逐条重发，可先不实现，但实现后效率更高。

---

## 3. 通用传感器 `POST /jarvis/sensor/{type}`

### 3.1 `/step_count`
```json
{"steps": 4821, "ts": 1734567890}
```
- 上传频率：`POWER_SAVER` 15min / `NORMAL` 5min / `REALTIME` 1min

### 3.2 `/location`
```json
{
  "location": "浙江省杭州市余杭区文一西路 XX 号",
  "latitude": 30.2814,
  "longitude": 120.0123,
  "accuracy": 12.5,
  "activity": "home",
  "ts": 1734567890
}
```
- `activity`: `home` / `office` / `other`（客户端通过地理围栏推断）
- 频率：`POWER_SAVER` 15min / `NORMAL` 5min / `REALTIME` 30s

### 3.3 `/accelerometer`
```json
{
  "magnitude": 10.32,
  "x": 0.12, "y": 9.81, "z": 1.03,
  "activity": "walking",
  "fall_detected": false,
  "still_duration_min": 0,
  "ts": 1734567890
}
```
- `activity`: `still` / `walking` / `running` / `vigorous`
- `fall_detected=true` 时后台建议立即推送告警/回拨
- 频率：5min / 1min / 10s

### 3.4 `/gyroscope`
```json
{
  "angular_speed": 0.23,
  "x": 0.05, "y": 0.21, "z": 0.08,
  "posture": "upright",
  "ts": 1734567890
}
```
- `posture`: `upright` / `lying` / `tilted`
- 频率：5min / 1min / 10s

### 3.5 `/light`
```json
{"lux": 420, "environment": "outdoor", "ts": 1734567890}
```
- `environment`: `dark` (<50 lux) / `indoor` (50–300) / `outdoor` (>300)
- 只在 lux 变化 ≥ 30 时上报（非周期）

### 3.6 `/bluetooth`
```json
{
  "device": "HUAWEI Band 9-AB12",
  "connected": true,
  "bonded_devices": 3,
  "adapter_enabled": true,
  "ts": 1734567890
}
```
- 事件驱动：ACL 连接变化或适配器开关时上报

### 3.7 `/sleep` *(v2.0 暂未接入，schema 占位)*
```json
{
  "duration_min": 432,
  "quality": "good",
  "deep_min": 90,
  "light_min": 300,
  "rem_min": 42,
  "ts": 1734567890
}
```

---

## 统一响应格式

**成功（200）**：
```json
{
  "success": true,
  "message": "ok",
  "accepted": 3,
  "rejected": 0,
  "action": null
}
```

**失败（4xx/5xx）**：
- 非 200 客户端直接视为失败、自动入库重试
- 建议 body 用 `{"success": false, "message": "reason"}` 方便排查
- `action` 字段预留给未来"让客户端切换模式/刷新 token"等指令

**最小可用响应**：
直接返回 `{}` 或纯文本 `ok` 也能跑通（客户端有 `lenient` 解析）。

---

## 可选鉴权

- 所有请求可能带 `token` 字段（Body 里）或 `Authorization` header（暂未实现）
- v2.0 alpha 不强制鉴权；后台可按需校验并返回 401

---

## 头部

客户端固定发送：
```
Content-Type: application/json; charset=utf-8
User-Agent: HRBridge-Android/2.0
```

---

## 可观测/降级策略（客户端行为，供后台参考）

1. **断网零丢失**：所有记录先写 Room，再异步批量上传
2. **失败退避**：WorkManager 指数退避（30s / 1min / 2min / …），最多重试 5 次
3. **批量降级**：`/batch` 返回 404 自动降级逐条 `/heart-rate`
4. **紧急心率**：`critical_*` 立即单条上传 + 切 5 分钟 REALTIME 模式

---

## 测试命令

```bash
# HR 单条
curl -X POST http://100.126.107.40:18890/jarvis/sensor/heart-rate \
  -H 'Content-Type: application/json' \
  -d '{"hr":75,"avg":75,"status":"test","trend":"stable","samples":1,"device":"curl","ts":1734567890}'

# 计步
curl -X POST http://100.126.107.40:18890/jarvis/sensor/step_count \
  -H 'Content-Type: application/json' \
  -d '{"steps":4821,"ts":1734567890}'

# 定位
curl -X POST http://100.126.107.40:18890/jarvis/sensor/location \
  -H 'Content-Type: application/json' \
  -d '{"location":"test","latitude":30.28,"longitude":120.01,"accuracy":10,"activity":"home","ts":1734567890}'
```
