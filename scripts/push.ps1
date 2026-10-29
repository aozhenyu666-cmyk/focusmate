<#
把当前工作树的已跟踪文件同步到 GitHub。

为什么要这个脚本，而不是 git push：
本机到 github.com:443 的直连被网络阻断（git fetch/push 一律
"Failed to connect to github.com:443"），但 api.github.com 正常。
所以走 REST API 建 blob/tree/commit 再移动 ref —— 效果等价于一次提交，
只是传输路径不同。

用法：
    powershell -ExecutionPolicy Bypass -File scripts\push.ps1
    powershell -ExecutionPolicy Bypass -File scripts\push.ps1 -Message "docs: 更新验收记录"

前置：
    gh auth status 必须已登录，且 token 有 repo 权限。
    本地先 git add + git commit，本脚本上传的是 git ls-files 的内容。
#>
param(
    [string]$Repo = 'aozhenyu666-cmyk/focusmate',
    [string]$Branch = 'main',
    [string]$Message = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Push-Location $root
try {
    if (-not $Message) { $Message = (git log -1 --pretty=%B) -join "`n" }

    $token = (gh auth token).Trim()
    if (-not $token) { throw 'gh 未登录，先执行 gh auth login' }
    $headers = @{
        Authorization = "Bearer $token"
        'User-Agent'  = 'focusmate-push'
        Accept        = 'application/vnd.github+json'
    }
    $api = "https://api.github.com/repos/$Repo"

    # 远端当前 ref 与 tree：作为本次提交的 parent / base_tree
    $remoteRef = Invoke-RestMethod -Uri "$api/git/ref/heads/$Branch" -Headers $headers
    $parentSha = $remoteRef.object.sha
    $parentCommit = Invoke-RestMethod -Uri "$api/git/commits/$parentSha" -Headers $headers
    $baseTree = $parentCommit.tree.sha

    $files = git ls-files
    $entries = New-Object System.Collections.ArrayList
    foreach ($f in $files) {
        $full = Join-Path $root $f
        if (-not (Test-Path $full)) { continue }
        $b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($full))
        $body = @{ content = $b64; encoding = 'base64' } | ConvertTo-Json -Compress
        $blob = Invoke-RestMethod -Method Post -Uri "$api/git/blobs" -Headers $headers -Body $body -ContentType 'application/json'
        $mode = if ($f -eq 'gradlew') { '100755' } else { '100644' }
        [void]$entries.Add(@{ path = ($f -replace '\\', '/'); mode = $mode; type = 'blob'; sha = $blob.sha })
    }
    Write-Host "已上传 $($entries.Count) 个文件对象"

    $treeBody = @{ base_tree = $baseTree; tree = $entries.ToArray() } | ConvertTo-Json -Depth 10 -Compress
    $tree = Invoke-RestMethod -Method Post -Uri "$api/git/trees" -Headers $headers -Body $treeBody -ContentType 'application/json'

    $commitBody = @{ message = $Message; tree = $tree.sha; parents = @($parentSha) } | ConvertTo-Json -Depth 5 -Compress
    $commit = Invoke-RestMethod -Method Post -Uri "$api/git/commits" -Headers $headers -Body $commitBody -ContentType 'application/json'

    $refBody = @{ sha = $commit.sha; force = $false } | ConvertTo-Json -Compress
    $null = Invoke-RestMethod -Method Patch -Uri "$api/git/refs/heads/$Branch" -Headers $headers -Body $refBody -ContentType 'application/json'

    Write-Host "已推送到 https://github.com/$Repo/tree/$Branch"
    Write-Host "commit: $($commit.sha)"
    Write-Host "parent: $parentSha"
}
finally {
    Pop-Location
}
