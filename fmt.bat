@echo off

call gradlew spotlessApply

if %errorlevel% neq 0 (
	exit /b 1
)

gofmt -w -s .

echo done
