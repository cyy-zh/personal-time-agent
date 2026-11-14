# Personal Time Agent

本仓库用于构建面向 ColorOS 16 的本地优先个人时间管理 Android 应用。完整产品需求见[产品需求原文](docs/product-requirements.md)，可追踪验收项见[验收矩阵](docs/acceptance-matrix.md)。

## 当前状态

开发候选版，尚未完成完整产品验收。已实现任务编辑与约束排程、目标/习惯提案、时间账本、API 配置、权限门控、使用统计/提醒/通知/视觉适配、模块设置及加密备份恢复。电脑端编译、7 个 JUnit 测试方法和 Android lint 已通过；这不代表全部功能或真机行为通过验收。

仍需全需求覆盖审计、端到端交互及数据库恢复测试、实际 API 联调、ColorOS 16 Widget 和系统权限实测、模型安装及人工标注准确性评估、七天统计和提醒可靠性验收。不能用当前 APK 替代用户要求的最终完整产品。详见[验证记录](docs/validation-report.md)和[验收矩阵](docs/acceptance-matrix.md)。

## 原则

- 本地数据是任务、日程、目标与时间记录的主要来源。
- AI 服务、通知采集、使用统计和可选视觉识别彼此解耦并可单独关闭。
- AI 对重要数据的操作受权限和确认边界约束。
- 未测试能力不得标记为通过；记录平台限制和证据。

## 构建

### 本地构建

需要 JDK 17、Android SDK（API 36）和 Gradle Wrapper。Windows PowerShell：

```powershell
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
```

macOS/Linux 将 `./gradlew.bat` 替换为 `./gradlew`。GitHub Actions 在每次 push 和 pull request 上执行 debug APK 构建、单元测试和 Android lint。构建依赖仓库可访问性及依赖版本解析也需持续验证。

## 使用说明

目前提供可安装的早期开发版本，首次启动后可浏览应用页面并试用已接入的基础任务与排程能力。系统级能力需在 Android 设置中由用户逐项授权；通知读取、使用情况访问和无障碍活动识别均为可选功能，默认不应视为已开启。功能状态及尚未验收的部分见[中文使用说明](docs/user-guide.md)和[验收矩阵](docs/acceptance-matrix.md)。

请勿将 API Key、个人数据、通知正文、原始截图、签名密钥或本机构建产物提交到仓库。

## 开源许可

本项目采用 Apache License 2.0，详见 [LICENSE](LICENSE)。
