# 对比一轮对话各环节的耗时。用法：
#   .\scripts\bench.ps1 -Label serial
#   .\scripts\bench.ps1 -Label parallel
param(
    [string]$Label = "run",
    [string]$BaseUrl = "http://127.0.0.1:8080",
    # 接入登录后，对话接口需要客户账号的令牌；默认用演示账号
    [string]$Username = "u1001",
    [string]$Password = "demo123"
)

$login = @{ username = $Username; password = $Password } | ConvertTo-Json
$token = (Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($login))).access_token
$headers = @{ Authorization = "Bearer $token" }

$questions = @(
    "我想申请退款，需要满足什么条件",
    "订单显示已发货但物流一直没有更新",
    "登录时提示 401 错误怎么办",
    "会员积分什么时候过期",
    "加急配送多久能送到",
    "忘记密码了怎么重置",
    "应用总是闪退怎么解决",
    "订单满多少可以免运费"
)

function Send-Chat($message, $conv) {
    $body = @{ message = $message; conv_id = $conv } | ConvertTo-Json
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/chat" -Headers $headers -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body))
}

$run = Get-Random
# 预热一次（建立连接、JIT 编译），不计入结果
Send-Chat "你好" "bench-$Label-$run-warmup" | Out-Null

$rows = @()
$i = 0
foreach ($q in $questions) {
    $i++
    $r = Send-Chat $q "bench-$Label-$run-$i"
    $t = $r.stage_timings_ms
    $rows += [pscustomobject]@{
        n         = $i
        total     = [int]$r.total_ms
        memory    = [int]$t.memory
        intent    = [int]$t.intent
        knowledge = [int]$t.knowledge
        generate  = [int]$t.generate
        verify    = [int]$t.verify
        persist   = [int]$t.persist
        intent_is = $r.intent
        degraded  = ($r.degraded_stages -join ",")
    }
}

$rows | Format-Table -AutoSize

$avg = [ordered]@{}
foreach ($col in "total", "memory", "intent", "knowledge", "generate", "verify", "persist") {
    $avg[$col] = [int](($rows | Measure-Object -Property $col -Average).Average)
}
$sorted = $rows | Sort-Object total
$median = $sorted[[int][math]::Floor(($sorted.Count - 1) / 2)].total
$max = ($rows | Measure-Object -Property total -Maximum).Maximum

Write-Host ("[{0}] 平均(ms): total={1}  memory={2}  intent={3}  knowledge={4}  generate={5}  verify={6}  persist={7}" -f `
    $Label, $avg.total, $avg.memory, $avg.intent, $avg.knowledge, $avg.generate, $avg.verify, $avg.persist)
Write-Host ("[{0}] total 中位数={1} ms，最大={2} ms，样本数={3}" -f $Label, $median, $max, $rows.Count)
