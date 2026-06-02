param(
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"

$pythonProject = $PSScriptRoot
$projectRoot = (Resolve-Path (Join-Path $pythonProject "..")).Path
$buildRoot = Join-Path $pythonProject "build"
$distPath = Join-Path $buildRoot "release"
$workPath = Join-Path $buildRoot "pyinstaller"
$specPath = Join-Path $buildRoot "spec"
$entryPoint = Join-Path $pythonProject "app.py"
$officialEntries = Join-Path $projectRoot "database\input\official_entries.json"
$googleServices = Join-Path $projectRoot "app\google-services.json"
$iconPath = Join-Path $projectRoot "app\src\main\res\drawable-xxxhdpi\ic_launcher_prechnik.png"
$releaseName = "mali-precnik-$Version-windows-x64"

foreach ($requiredPath in ($entryPoint, $officialEntries, $iconPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath)) {
        throw "Недостаје фајл потребан за изградњу: $requiredPath"
    }
}

$arguments = @(
    "-m", "PyInstaller",
    "--noconfirm",
    "--clean",
    "--onefile",
    "--windowed",
    "--name", $releaseName,
    "--distpath", $distPath,
    "--workpath", $workPath,
    "--specpath", $specPath,
    "--paths", $pythonProject,
    "--icon", $iconPath,
    "--add-data", "$officialEntries;database/input",
    "--add-data", "$iconPath;assets"
)

if (Test-Path -LiteralPath $googleServices) {
    $arguments += @("--add-data", "$googleServices;app")
} else {
    Write-Warning "app/google-services.json није пронађен; EXE ће радити без онлајн предлога и гласања."
}

$arguments += $entryPoint

python @arguments
if ($LASTEXITCODE -ne 0) {
    throw "PyInstaller изградња није успела (излазни код: $LASTEXITCODE)."
}

$outputPath = Join-Path $distPath "$releaseName.exe"
Write-Host ""
Write-Host "Готов Windows EXE:"
Write-Host $outputPath
