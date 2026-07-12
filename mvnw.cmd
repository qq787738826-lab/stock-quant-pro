@echo off
setlocal
set "MAVEN_VERSION=3.9.16"
set "MAVEN_BASE=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%"
set "MAVEN_HOME=%MAVEN_BASE%\apache-maven-%MAVEN_VERSION%"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo [Stock Quant Pro] First run: downloading Apache Maven %MAVEN_VERSION%...
  if not exist "%MAVEN_BASE%" mkdir "%MAVEN_BASE%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $url='https://dlcdn.apache.org/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip'; $zip=Join-Path $env:TEMP 'apache-maven-%MAVEN_VERSION%-bin.zip'; Invoke-WebRequest -UseBasicParsing $url -OutFile $zip; Expand-Archive -Path $zip -DestinationPath '%MAVEN_BASE%' -Force; Remove-Item $zip -Force"
  if errorlevel 1 (
    echo [ERROR] Maven download failed. Install Maven 3.9+ manually, then run mvn.
    exit /b 1
  )
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
