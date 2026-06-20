@echo off

pushd "%~dp0"

set exitcode=0

go build -C go_wrapper -o gdep.exe -gcflags="-e"

if %errorlevel% neq 0 (
	set exitcode=1
	goto END
)

call gradlew build -x spotlessJavaCheck --rerun-tasks

if %errorlevel% neq 0 (
	set exitcode=1
	goto END
)

rmdir /S /Q out
mkdir out

copy /Y build\libs\gdep.jar out\gdep.jar
copy /Y go_wrapper\gdep.exe out\gdep.exe

if "%~1" EQU "--copy-scripts" (
	copy /Y scripts_windows\*.bat out\
)

echo done

:END

popd

exit /b %exitcode%
