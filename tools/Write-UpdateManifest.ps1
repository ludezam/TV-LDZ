param(
    [Parameter(Mandatory=$true)][string]$ApkPath,
    [Parameter(Mandatory=$true)][string]$Version,
    [Parameter(Mandatory=$true)][string]$Tag,
    [Parameter(Mandatory=$true)][string]$PackageName,
    [string]$OutputPath = 'update.json'
)
$ErrorActionPreference = 'Stop'
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$item = Get-Item -LiteralPath $resolvedApk
$sha = (Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($item.Extension -ne '.apk') { throw 'ApkPath must point to an .apk file.' }
if ($Tag -notmatch '^v?[0-9]+(\.[0-9]+){1,3}$' -or ($Tag -replace '^[vV]', '') -ne $Version) { throw 'Tag must match Version, with an optional leading v.' }
if ($sha -notmatch '^[0-9a-f]{64}$') { throw 'Could not calculate a valid SHA-256.' }
$manifest = [ordered]@{
    schema = 1
    version = $Version
    tag = $Tag
    apk = $item.Name
    size = [long]$item.Length
    sha256 = $sha
    package = $PackageName
}
$json = $manifest | ConvertTo-Json -Depth 3
$absoluteOutput = [System.IO.Path]::GetFullPath($OutputPath)
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($absoluteOutput)) | Out-Null
[System.IO.File]::WriteAllText($absoluteOutput, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
Write-Output "Wrote $absoluteOutput"
