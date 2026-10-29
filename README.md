# 专注伙伴 FocusMate

一期只做一件事：**专注限制真实生效、到点真的恢复。**

## 现在能做什么

- 首页一个按钮「进入沉浸舱 · 10 分钟」：切到 Gemini，接下来十分钟只有 Gemini、通话、微信可用，
  其它应用被全屏拦截层挡住，到点本地自动解除。规则见 `docs/DECISIONS.md` D-07。
- 「先用 5 分钟单应用试一次」用于短时长验证同一条限制链路。

## 现在**不**做什么（不要误以为有）

- 不锁屏、不挂起应用、不 Force Stop、不用 Shizuku、不用设备所有者。
- 没有角色人格、没有主动开口、没有记忆与复盘（阶段 2/3）。
- 不防用户自己去关权限或强行停止本 APP——那属于用户主动解除，应用只如实上报状态。

## 构建

环境已固定（原因见 `docs/DECISIONS.md` D-10：非 ASCII 工程路径会阻断 AGP 与 Gradle test worker）：

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$env:GRADLE_USER_HOME='C:\gh'
$env:TEMP='C:\dsh-tmp'; $env:TMP='C:\dsh-tmp'
C:\gradle\gradle-8.11.1\bin\gradle.bat :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

## 文档

- `docs/DECISIONS.md` 已确认规则与未决问题（Q-01～Q-07）
- `docs/STATE.md` 当前阶段、版本、已验证/未验证
- `docs/SOURCES.md` 上游来源与本地实现的对应
- `docs/ACCEPTANCE.md` 用例、证据、阶段门槛判断

## 上传到 GitHub

仓库：https://github.com/aozhenyu666-cmyk/focusmate （private）

本机到 `github.com:443` 的直连被网络阻断，`git push` 会报
`Failed to connect to github.com:443`；但 `api.github.com` 正常。
所以推送走 REST API：`git commit` 照常做，然后用

```powershell
node scripts\push.mjs
```

脚本按 `git ls-files` 上传 blob、建**全量** tree、建 commit、移动 `main` ref。
用全量 tree 而不是 base_tree 增量，是因为增量模式删文件要额外塞 `sha:null` 条目，
漏了就变成「本地已删、远端还在」。

**代价（必须知道）**：脚本拼出来的 commit SHA 与本地不同，两边历史最初是不相关的两条线。
本机后来网络恢复（`git push` 已可用），于是用 `git merge origin/main --allow-unrelated-histories`
把远端那条历史接回本地，冲突只有 `gradlew` 的权限位（内容一致）。
现在两边同一条历史，日常一律用 `git push`。
这段说明保留，是因为换机器或网络再次抽风时会遇到同样的问题。
