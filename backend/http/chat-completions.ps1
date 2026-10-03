# Przykładowe requesty do POST /v1/chat/completions (Basic auth). Działa w Windows PowerShell 5.1 i 7.
# Użycie:  .\chat-completions.ps1 [-Scenario ok|pii|bad-model|no-auth] [-Login x] [-Password y] [-Hostname http://...]
param(
    [ValidateSet('ok', 'pii', 'bad-model', 'no-auth')]
    [string]$Scenario = 'ok',
    [string]$Login = $(if ($env:CL_LOGIN) { $env:CL_LOGIN } else { 'agent-sdk' }),
    [string]$Password = $(if ($env:CL_PASSWORD) { $env:CL_PASSWORD } else { 'agent-sdk' }),
    [string]$Hostname = $(if ($env:CL_HOST) { $env:CL_HOST } else { 'http://localhost:8000' })
)

$url = "$Hostname/v1/chat/completions"
$model = 'qwen2.5:1.5b-instruct-q4_K_M'

# Polskie znaki jako escapy JSON (\uXXXX), żeby kodowanie konsoli niczego nie zepsuło.
switch ($Scenario) {
    'ok'        { $body = @{ model = $model; messages = @(@{ role = 'user'; content = 'Cześć, napisz jedno zdanie o Krakowie.' }) } }
    'pii'       { $body = @{ model = $model; messages = @(@{ role = 'user'; content = 'Mój PESEL to 44051401359, a email jan.kowalski@example.com. Powtórz je.' }) } }
    'bad-model' { $body = @{ model = 'gpt-unknown'; messages = @(@{ role = 'user'; content = 'test' }) } }
    'no-auth'   { $body = @{ model = 'qwen2.5:0.5b'; messages = @(@{ role = 'user'; content = 'test' }) } }
}

# ConvertTo-Json zamieniłby "\u" na "\\u", więc budujemy JSON ręcznie z tych samych danych.
$msg = $body.messages[0]
$json = '{"model":"' + $body.model + '","messages":[{"role":"' + $msg.role + '","content":"' + $msg.content + '"}]}'

$headers = @{}
if ($Scenario -ne 'no-auth') {
    $pair = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Login}:${Password}"))
    $headers['Authorization'] = "Basic $pair"
}

try {
    $response = Invoke-WebRequest -Method Post -Uri $url -Headers $headers `
        -ContentType 'application/json; charset=utf-8' `
        -Body ([Text.Encoding]::UTF8.GetBytes($json)) -UseBasicParsing
} catch {
    # Odpowiedzi 4xx/5xx (np. 401, 403, 502) też niosą ciekawy JSON z trace'em.
    $response = $_.Exception.Response
    if ($null -eq $response) { throw }
    $status = [int]$response.StatusCode
    Write-Host "HTTP $status"
    $_.ErrorDetails.Message
    return
}

Write-Host "HTTP $($response.StatusCode)"
$response.Content
