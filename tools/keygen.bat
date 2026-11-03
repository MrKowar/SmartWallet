@echo off
rem Wrapper for KeyGen.java: UTF-8 console output on Windows
chcp 65001 >nul
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dstdout.encoding=UTF-8 "%~dp0KeyGen.java" %*
