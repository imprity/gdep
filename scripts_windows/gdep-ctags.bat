@echo off

setlocal 

set "GDEP_EXE=%~dp0gdep.exe"

"%GDEP_EXE%" files | ctags -L - %*

endlocal

