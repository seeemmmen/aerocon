param(
    [switch]$BuildOnly
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$projectDirectory = $PSScriptRoot
$toolsDirectory = Join-Path $projectDirectory '.tools'
$jdkDirectory = Join-Path $toolsDirectory 'jdk-21.0.13.1+2'
$mavenDirectory = Join-Path $toolsDirectory 'apache-maven-3.9.16'

function Get-VerifiedArchive {
    param(
        [string]$Url,
        [string]$ArchivePath,
        [string]$Algorithm,
        [string]$Checksum
    )

    if (-not (Test-Path -LiteralPath $ArchivePath)) {
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $ArchivePath
    }
    $actualChecksum = (Get-FileHash -LiteralPath $ArchivePath -Algorithm $Algorithm).Hash
    if ($actualChecksum -ne $Checksum) {
        throw "Checksum mismatch for $ArchivePath. Remove that archive and try again."
    }
    Expand-Archive -LiteralPath $ArchivePath -DestinationPath $toolsDirectory -Force
}

New-Item -ItemType Directory -Force -Path $toolsDirectory | Out-Null

if (-not (Test-Path -LiteralPath (Join-Path $jdkDirectory 'bin\javac.exe'))) {
    Write-Host 'Downloading a local Temurin JDK 21 (first run only)...'
    Get-VerifiedArchive `
        -Url 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13.1_2.zip' `
        -ArchivePath (Join-Path $toolsDirectory 'jdk-download.zip') `
        -Algorithm SHA256 `
        -Checksum 'bbebea1469996b276683c9299c227b294f85c847039c82633218186a4ad4fbf2'
}

if (-not (Test-Path -LiteralPath (Join-Path $mavenDirectory 'bin\mvn.cmd'))) {
    Write-Host 'Downloading local Maven (first run only)...'
    Get-VerifiedArchive `
        -Url 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip' `
        -ArchivePath (Join-Path $toolsDirectory 'maven-download.zip') `
        -Algorithm SHA512 `
        -Checksum 'ed41650d42485cfc243fad22158caf9cbb5dc408ce7a09ddb94dd42a019de929ca43065bfa450612cf12bf78b5cafa3884b96c090de326ff590448c933454af3'
}

$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
try {
    $env:JAVA_HOME = $jdkDirectory
    $env:PATH = (Join-Path $jdkDirectory 'bin') + [IO.Path]::PathSeparator + $previousPath
    Push-Location -LiteralPath $projectDirectory
    try {
        if ($BuildOnly) {
            & (Join-Path $mavenDirectory 'bin\mvn.cmd') --batch-mode package
        } else {
            & (Join-Path $mavenDirectory 'bin\mvn.cmd') --batch-mode compile javafx:run
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Maven exited with code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
}
