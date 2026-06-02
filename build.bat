@echo off

pushd "%~dp0"

set exitcode=0

call gradlew build -x spotlessJavaCheck

if %errorlevel% neq 0 (
	set exitcode=1
	goto END
)

go build -C go_wrapper -o gdep.exe

if %errorlevel% neq 0 (
	set exitcode=1
	goto END
)

rmdir /S /Q out
mkdir out

copy /Y build\libs\gdep.jar out\gdep.jar
copy /Y go_wrapper\gdep.exe out\gdep.exe

echo done

:END

popd

exit /b %exitcode%
