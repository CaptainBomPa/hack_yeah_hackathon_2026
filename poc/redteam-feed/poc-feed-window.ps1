# POC: pobiera podatnosci z oknia "wczoraj" (UTC) z NVD, OSV i GitHub Advisories.
# Rozroznia NOWE (published w oknie) od ZMIENIONYCH (lastModified w oknie, published wczesniej).
# Uruchomienie: powershell -ExecutionPolicy Bypass -File poc-feed-window.ps1 [-Day 2026-10-02]
# GitHub: opcjonalnie ustaw $env:GITHUB_TOKEN (bez tokena GHSA jest pomijane, bo limit zapytan).

param(
    [string]$Day = ([DateTime]::UtcNow.AddDays(-1).ToString('yyyy-MM-dd')),
    [int]$OsvDetailLimit = 20,
    [string]$GithubToken = $env:GITHUB_TOKEN
)

$start = [DateTime]::ParseExact($Day, 'yyyy-MM-dd', $null)
$end = $start.AddDays(1)
$startIso = $start.ToString('yyyy-MM-ddTHH:mm:ss.000')
$endIso = $end.ToString('yyyy-MM-ddTHH:mm:ss.000')

Write-Host "=== Okno UTC: $startIso .. $endIso ==="

# ---------- NVD: NOWE (pubStartDate) vs ZMIENIONE (lastModStartDate) ----------
Write-Host "`n--- NVD ---"
try {
    $nvdNew = Invoke-RestMethod -Uri "https://services.nvd.nist.gov/rest/json/cves/2.0?pubStartDate=$startIso&pubEndDate=$endIso&resultsPerPage=2000" -TimeoutSec 60
    Write-Host "NOWE (published w oknie): $($nvdNew.totalResults)"
    $nvdNew.vulnerabilities | Select-Object -First 10 | ForEach-Object {
        $c = $_.cve
        $desc = ($c.descriptions | Where-Object lang -eq 'en').value
        if ($desc.Length -gt 110) { $desc = $desc.Substring(0, 110) + '...' }
        Write-Host ("  {0} | published {1} | {2}" -f $c.id, $c.published, $desc)
    }
} catch {
    Write-Host "NVD (nowe) blad: $($_.Exception.Message)"
}

Start-Sleep -Seconds 6   # limit NVD bez klucza: 5 zapytan / 30 s

try {
    $nvdMod = Invoke-RestMethod -Uri "https://services.nvd.nist.gov/rest/json/cves/2.0?lastModStartDate=$startIso&lastModEndDate=$endIso&resultsPerPage=2000" -TimeoutSec 60
    $updatedOnly = @($nvdMod.vulnerabilities | Where-Object {
        [DateTime]::Parse($_.cve.published) -lt $start
    })
    Write-Host "ZMIENIONE (lastModified w oknie, ogolem): $($nvdMod.totalResults)"
    Write-Host "  z czego tylko aktualizacje starych wpisow (published przed oknem): $($updatedOnly.Count)"
} catch {
    Write-Host "NVD (zmienione) blad: $($_.Exception.Message)"
}

# ---------- GitHub Advisories: published w oknie ----------
Write-Host "`n--- GitHub Advisories ---"
if (-not $GithubToken) {
    Write-Host "POMINIETE: brak tokena (ustaw `$env:GITHUB_TOKEN); bez tokena limit zapytan blokuje wywolanie."
} else {
    $ghUrl = "https://api.github.com/advisories?published=$($start.ToString('yyyy-MM-dd'))..$($end.ToString('yyyy-MM-dd'))&per_page=100"
    $headers = @{
        Accept                 = 'application/vnd.github+json'
        Authorization          = "Bearer $GithubToken"
        'X-GitHub-Api-Version' = '2022-11-28'
    }
    try {
        $gh = Invoke-RestMethod -Uri $ghUrl -Headers $headers -TimeoutSec 60
        Write-Host "opublikowane w oknie: $($gh.Count)"
        $gh | Select-Object -First 10 | ForEach-Object {
            $pkgs = ($_.vulnerabilities | ForEach-Object { "$($_.ecosystem):$($_.package.name)" } | Select-Object -Unique) -join ','
            Write-Host ("  {0} | {1} | {2} | [{3}] | {4}" -f $_.ghsa_id, $_.severity, ($_.cwes.cwe_id -join ','), $pkgs, $_.summary)
        }
    } catch {
        Write-Host "GHSA blad: $($_.Exception.Message)"
    }
}

# ---------- OSV: plik modified_id.csv per ekosystem (posortowany malejaco po czasie) ----------
Write-Host "`n--- OSV (modified_id.csv z GCS) ---"
$ecosystems = @('PyPI', 'npm')
foreach ($eco in $ecosystems) {
    $csvUrl = "https://osv-vulnerabilities.storage.googleapis.com/$eco/modified_id.csv"
    $ids = @()
    try {
        $lines = (Invoke-WebRequest -Uri $csvUrl -UseBasicParsing -TimeoutSec 120).Content -split "`n"
    } catch {
        Write-Host "$eco blad: $($_.Exception.Message)"
        continue
    }
    foreach ($line in $lines) {
        $parts = $line.Trim() -split ','
        if ($parts.Count -lt 2) { continue }
        $ts = [DateTime]::Parse($parts[0]).ToUniversalTime()
        if ($ts -ge $start -and $ts -lt $end) { $ids += $parts[1] }
        elseif ($ts -lt $start) { break }   # plik jest posortowany malejaco
    }
    Write-Host "$eco : rekordow zmienionych w oknie = $($ids.Count) (z $($lines.Count) wierszy w pliku)"

    # Szczegoly tylko dla pierwszych N; published pozwala odroznic nowe od zmienionych
    foreach ($id in ($ids | Select-Object -First $OsvDetailLimit)) {
        try {
            $v = Invoke-RestMethod -Uri "https://api.osv.dev/v1/vulns/$id" -TimeoutSec 30
            $pkgs = ($v.affected | ForEach-Object { $_.package.name } | Select-Object -Unique) -join ','
            $cwes = ($v.database_specific.cwe_ids) -join ','
            $kind = if ([DateTime]::Parse($v.published).ToUniversalTime() -ge $start) { 'NOWE' } else { 'ZMIANA' }
            Write-Host ("  [{0}] {1} | published {2} | pkg [{3}] | cwe [{4}] | {5}" -f $kind, $v.id, $v.published, $pkgs, $cwes, $v.summary)
        } catch {
            Write-Host "  $id blad szczegolow: $($_.Exception.Message)"
        }
    }
}

Write-Host "`n=== Koniec POC ==="
