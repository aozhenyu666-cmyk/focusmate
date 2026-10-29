# 网络诊断：github.com 的 git push 为什么不稳

日期：2026-10-29。本机 Windows + 家宽直连，无代理。

## 结论先行

**是网络问题，但不是"断网"或"配置错了"，是这条链路上对 `github.com` 的
间歇性丢包/阻断。** 同一个域名有时通、有时 8 秒超时，而同一时刻
`api.github.com` 100% 通、`http://github.com`（80 端口）100% 通。

## 实测数据

### 1. 不同端点成功率（各 5 次，同一分钟内）

| 端点 | 结果 |
|---|---|
| `https://github.com/` | 3 成功 / 2 超时（8s 无响应） |
| `https://api.github.com/` | 5 成功 / 0 失败，TLS 握手 0.17~0.22s |
| `http://github.com/`（80 端口） | 5 成功 / 0 失败，返回 301 |
| `https://codeload.github.com/` | 成功，TLS 0.23s |
| `https://raw.githubusercontent.com/` | 成功，TLS 0.28s |
| `ssh.github.com:443` | 10 次 TCP 全通，SSH 握手成功（返回 publickey denied，说明服务端在正常应答） |

### 2. 失败时的确切形态

```
tcp=0.000000;tls=0.000000;total=8.002970;code=000
curl: (28) Connection timed out after 8002 milliseconds
```

注意 `tcp=0`：**TCP 三次握手都没完成**，不是 TLS 阶段被拦、不是证书问题、不是 DNS 问题
（DNS 稳定解析到 `20.205.243.166`，且 `Test-NetConnection` 单独测该 IP:443 是通的）。

### 3. 排除掉的可能原因

| 怀疑 | 实测 | 结论 |
|---|---|---|
| DNS 污染/解析错 | `github.com` → `20.205.243.166`，与 `api.github.com` → `20.205.243.168` 同一网段，都正常 | 排除 |
| 代理配置错 | `git http.proxy` / `https.proxy` / `HTTP_PROXY` / `ALL_PROXY` 全为空，`netsh winhttp` 显示 Direct | 排除 |
| 凭据错 | `gh auth status` 正常，token 有 repo 权限；API 建 commit/改可见性都成功 | 排除 |
| 证书/SNI 问题 | 失败发生在 `tcp=0`，根本没走到 TLS | 排除 |
| 仓库太大 / 超时 | 只有 76 个文件，仓库很小；且失败时是 0 字节收到 | 排除 |
| 账号被限流 | 成功的那几次 HTTP 200，API 侧也未报限流 | 排除 |

### 4. 一个看似矛盾但关键的观察

用 `curl --resolve github.com:443:<ip>` 强制指定 IP 时，无论指向
`140.82.112.4` 还是 `20.205.243.166`，**都是超时**；而 `Test-NetConnection`
单独测同一个 IP 的 443 端口却是 `True`。

这不是矛盾，而是说明：**TCP 握手能完成与否取决于"什么时候测"**，
链路本身在抖动/被限速，不是某个 IP 稳定可用或稳定不可用。
换句话说，把 DNS 固定到某个 IP、或换 `--resolve` 都解决不了这个问题。

## 应对：已经落地的两条路径

### 主路径：正常 `git push`

网络好的时候直接 `git push`，工作正常。2026-10-29 这次诊断结束后，
`git fetch` 与 `git push` 都已成功：

```
6df756e..997724c  main -> main
```

### 备用路径：`scripts/push.mjs` 走 REST API

`git push` 失败时用它。原理是 `api.github.com`（不抖）建 blob/tree/commit 再移动 ref。

**代价必须记住**：这样推出来的 commit SHA 与本地不同，两边历史会分叉成两条线。
网络恢复后要用

```
git fetch origin
git merge origin/main --allow-unrelated-histories -X ours --no-edit
git push origin main
```

接回来。`-X ours` 是因为内容本来就一致，冲突只可能出现在权限位这类元数据上。
本次已经这样接回一次，现在本地与远端同为 `997724c`。

## 这不是这个项目独有的问题

同一台机器上阶段 1 也遇到过完全一样的情况（见 README「上传到 GitHub」一节）。
换机器或换网络环境大概率不会复现。如果长期如此，只有三种真正的解法：

1. 用能稳定连通的网络（手机热点常比部分家宽好）；
2. 配一个你自己控制的代理，并在 git 里显式设置 `http.proxy`；
3. 改用 `ssh.github.com:443`（实测 10/10 通）——但需要先把公钥加到 GitHub 账号，
   本仓库没有也不应该保存任何私钥。

前两条都需要你决定，我没有替你配代理或动系统网络设置。
