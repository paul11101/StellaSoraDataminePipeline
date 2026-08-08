@echo off
setlocal
set "APP_HOME=%~dp0"

if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java.exe"
)

if not exist "%JAVA_EXE%" if defined JAVA_HOME (
    echo ERROR: JAVA_HOME does not point to a valid JDK: %JAVA_HOME% 1>&2
    exit /b 1
)

"%JAVA_EXE%" "-Dorg.gradle.appname=gradlew" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
