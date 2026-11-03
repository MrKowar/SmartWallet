@echo off
rem Build signed release APK and prepare update files in the dist folder
rem Example: tools\publish.bat --version-code 3 --version-name 1.2 --changelog "Credits and loans"
chcp 65001 >nul
cd /d "%~dp0.."
call gradlew.bat :app:assembleRelease || exit /b 1
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 "%~dp0Publish.java" %*
