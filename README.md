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

## 阶段 2：主动伙伴（新增）

底部多了一个「伙伴」页签，四个子页：

- **对话**：你说话他回应。说「十分钟后再聊」会排一条跟进，**只推迟对话，不改专注时长**。
- **他记得的**：每条事实都标来源（你说的 / 我看到的 / 我猜的），可"更正"或"忘掉"；
  更正/忘掉的条目不进上下文，但仍留在列表里（墓碑，不是物理删除）。
- **排着的事**：每条事项显示状态、版本、依据；可"推迟 10 分钟"或"取消"。
  同一话题再约定一次，旧的变"已取消"，不会两条并存。
- **设置**：模型接口/模型名/API Key（只存本机，不入库不入日志）+ 当前主动规则。

主动规则的默认值是**保守的、未经你确认的**：
两次之间 90~240 分钟、每天最多 3 次、23:00-08:00 免打扰、**不主动闲聊**（只在有约定或没说完的事时开口）。
专注进行中一律不开口。

Android 后台的真实边界：前台服务 60 秒判定一次，Doze/省电会推迟，
「强行停止」后不会自动恢复。**不承诺准点。**

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
- `docs/NETWORK.md` 本机 `git push` 不稳定的实测诊断与应对

## 上传到 GitHub

仓库：https://github.com/aozhenyu666-cmyk/focusmate

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
