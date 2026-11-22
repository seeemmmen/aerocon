$ErrorActionPreference = 'Stop'

# Build production and standalone smoke-test classes with the same local tools.
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly

[xml]$pom = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pom.xml')
$javafxVersion = $pom.project.properties.'javafx.version'
$repository = Join-Path $env:USERPROFILE '.m2\repository\org\openjfx'
$modulePath = (@('javafx-base', 'javafx-graphics', 'javafx-controls', 'javafx-fxml') | ForEach-Object {
    $jar = Join-Path $repository "$_\$javafxVersion\$_-$javafxVersion-win.jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "Missing JavaFX dependency: $jar" }
    $jar
}) -join [IO.Path]::PathSeparator
$java = Join-Path $PSScriptRoot '.tools\jdk-21.0.13.1+2\bin\java.exe'
$classPath = (Join-Path $PSScriptRoot 'target\test-classes') + [IO.Path]::PathSeparator + (Join-Path $PSScriptRoot 'target\classes')

Push-Location -LiteralPath $PSScriptRoot
try {
    & $java --module-path $modulePath --add-modules javafx.controls,javafx.fxml -cp $classPath pl.aerocon.PrototypeSmokeTest
    if ($LASTEXITCODE -ne 0) { throw "Prototype smoke test failed with code $LASTEXITCODE." }
} finally {
    Pop-Location
}
