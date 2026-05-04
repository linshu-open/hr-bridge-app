# APP 构建备忘录

## 项目信息
- **仓库**: https://github.com/linshu-open/hr-bridge-app/
- **当前分支**: `feature/ci-build` (基于 `rewrite/v2.0`)
- **版本**: Sensor Bridge 2.0
- **构建方式**: GitHub Actions CI

## 触发构建
### 自动触发
- `push` 到 `feature/ci-build` 分支
- `push` 到 `rewrite/**` 分支
- `pull_request` 到 `main` 或 `master`
- 打 `v*` 标签（如 `v2.1.0`）

### 手动触发
- GitHub Actions 页面 → Build APK → Run workflow

## 构建产物
| 类型 | 路径 | 说明 |
|------|------|------|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` | 日常测试 |
| Release APK | `app/build/outputs/apk/release/app-release.apk` | 需配置签名密钥 |

## 下载方式
1. **Artifacts**: GitHub Actions 运行完成后，在 Summary 页面下载 `app-debug` artifact
2. **Release**: 打 `v*` 标签后自动创建 Release，附带 Release APK

## 环境要求
- JDK 17 (Temurin)
- Gradle 8.7 (通过 setup-gradle action)
- Android SDK (通过 setup-android action，如需要)

## 签名配置（Release）
需在仓库 Secrets 中配置：
- `KEYSTORE_BASE64`: base64 编码的 release.keystore
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

## 分支策略
- `rewrite/v2.0`: 主开发分支
- `feature/ci-build`: CI 配置分支（本分支）
- `feature/**`: 功能分支
- `main` / `master`: 稳定分支

## MCP 集成状态
- `McpClient.kt`: SSE 客户端实现 ✅
- `SensorRepository.flushMcp()`: 传感器数据上传 ✅
- 依赖: `okhttp-sse` 已添加 ✅

## 注意事项
- 若构建失败，检查 `gradle/libs.versions.toml` 和 `app/build.gradle.kts` 依赖版本
- Debug APK 可直接安装测试
- Release APK 需签名才能安装到非调试设备

---
*创建于 2026-05-04 | 维护者: JARVIS PAI Team*
