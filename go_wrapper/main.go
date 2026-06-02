package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"os"
	"os/exec"
	"path/filepath"
)

type Settings struct {
	JavaHome *string
	CacheDir *string
}

var ErrLogger = log.New(os.Stderr, "GDEP_GO_WRAPPER_ERROR:", log.Lshortfile)

// TODO: maybe check java version

func main() {
	execPath, err := os.Executable()
	if err != nil {
		ErrLogger.Fatalf("could not get executable's path: %v", err)
	}

	execDir := filepath.Dir(execPath)

	settingsFile := filepath.Join(execDir, "gdep-settings.json")

	var settings *Settings = new(Settings)

	settingsJson, err := os.ReadFile(settingsFile)
	if err != nil {
		if !errors.Is(err, fs.ErrNotExist) {
			ErrLogger.Fatalf("could not open settings at \"%s\": %v", settingsFile, err)
		}
	} else {
		err := json.Unmarshal(settingsJson, settings)
		if err != nil {
			ErrLogger.Fatalf("failed to parse \"%s\": %v", settingsFile, err)
		}
	}

	if settings.JavaHome == nil {
		javaHome := (os.Getenv("JAVA_HOME"))
		settings.JavaHome = &javaHome
	}

	if settings.CacheDir == nil {
		cacheDir := filepath.Join(execDir, "gdep-cache")
		settings.CacheDir = &cacheDir
	}

	err = os.MkdirAll(*settings.CacheDir, 0775)
	if err != nil {
		ErrLogger.Fatalf("failed to create directory \"%s\": %v", *settings.CacheDir, err)
	}

	javaExe := filepath.Join(*settings.JavaHome, "bin", "java")

	gdepJarPath := filepath.Join(execDir, "gdep.jar")

	var args []string

	args = append(
		args,
		fmt.Sprintf("-Dgdep.internal.cache.dir=%s", *settings.CacheDir),
		"-jar",
		gdepJarPath,
	)

	args = append(
		args,
		os.Args[1:]...,
	)

	cmd := exec.Command(
		javaExe,
		args...,
	)

	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	err = cmd.Run()

	var exitError *exec.ExitError
	if err != nil && !errors.As(err, &exitError) {
		ErrLogger.Fatalf("failed to execute \"%s\": %v", cmd.String(), err)
	}
}
