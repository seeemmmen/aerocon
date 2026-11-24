param(
    [string]$DatabaseConfig,
    [string]$TestDatabaseConfig
)

$ErrorActionPreference = 'Stop'

# Build production and standalone smoke-test classes with the same local tools.
$mavenArguments = @()
if ($TestDatabaseConfig) {
    $mavenArguments += '-Daerocon.test.db.config=' + (Resolve-Path -LiteralPath $TestDatabaseConfig).Path
}
& (Join-Path $PSScriptRoot 'run.ps1') -BuildOnly -MavenArguments $mavenArguments

$jdkDirectory = Join-Path $PSScriptRoot '.tools\jdk-21.0.13.1+2'
$maven = Join-Path $PSScriptRoot '.tools\apache-maven-3.9.16\bin\mvn.cmd'
$classpathFile = Join-Path $PSScriptRoot 'target\runtime-classpath.txt'
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $PSScriptRoot
try {
    $env:JAVA_HOME = $jdkDirectory
    & $maven --batch-mode --no-transfer-progress org.apache.maven.plugins:maven-dependency-plugin:3.9.0:build-classpath '-DincludeScope=runtime' "-Dmdep.outputFile=$classpathFile"
    if ($LASTEXITCODE -ne 0) { throw "Could not resolve the runtime classpath (Maven code $LASTEXITCODE)." }

    $runtimeEntries = (Get-Content -LiteralPath $classpathFile -Raw).Trim().Split([IO.Path]::PathSeparator)
    $moduleEntries = @($runtimeEntries | Where-Object { [IO.Path]::GetFileName($_) -match '^javafx-(base|graphics|controls|fxml)-.+-win\.jar$' })
    if ($moduleEntries.Count -ne 4) { throw 'Expected four Windows JavaFX runtime modules in the resolved dependencies.' }
    $modulePath = $moduleEntries -join [IO.Path]::PathSeparator
    $classPathEntries = @(
        (Join-Path $PSScriptRoot 'target\test-classes')
        (Join-Path $PSScriptRoot 'target\classes')
    ) + @($runtimeEntries | Where-Object { [IO.Path]::GetFileName($_) -notmatch '^javafx-(base|graphics|controls|fxml)-' })
    $classPath = $classPathEntries -join [IO.Path]::PathSeparator
    $javaArguments = @('--module-path', $modulePath, '--add-modules', 'javafx.controls,javafx.fxml', '-cp', $classPath)
    if ($DatabaseConfig) {
        $javaArguments += '-Daerocon.db.config=' + (Resolve-Path -LiteralPath $DatabaseConfig).Path
    }
    $javaArguments += 'pl.aerocon.PrototypeSmokeTest'
    & (Join-Path $jdkDirectory 'bin\java.exe') @javaArguments
    if ($LASTEXITCODE -ne 0) { throw "Prototype smoke test failed with code $LASTEXITCODE." }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
