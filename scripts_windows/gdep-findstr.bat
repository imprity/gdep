@echo off

if "%1" == "" (
	echo [ERROR]: no arguments provided 1>&2
	exit /b 1
)

setlocal 

set "GDEP_EXE=%~dp0gdep.exe"

"%GDEP_EXE%" files | findstr /F:/ %*

endlocal
