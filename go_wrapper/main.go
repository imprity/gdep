package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"os"
	"path/filepath"
)

type Settings struct {
	JavaHome *string
	CacheDir *string
}

var ProgramInfo struct {
	JavaHome string
	CacheDir string
	ExecDir  string
	Cwd      string
}

var ErrLogger = log.New(os.Stderr, "ERROR:", log.Lshortfile)

// TODO: maybe check java version

func main() {
	// ===========================
	// setting up ProgramInfo
	// ===========================

	toAbsolute := func(p string) string {
		abs, err := filepath.Abs(p)
		if err != nil {
			ErrLogger.Fatalf("failed to get absolute path of %s: %v", p, err)
		}
		return abs
	}

	// Cwd
	ProgramInfo.Cwd = toAbsolute(os.Args[0])

	// ExecDir
	{
		execPath, err := os.Executable()
		if err != nil {
			ErrLogger.Fatalf("could not get executable's path: %v", err)
		}
		execDir := filepath.Dir(execPath)

		ProgramInfo.ExecDir = toAbsolute(execDir)
	}

	// JavaHome
	// CacheDir

	{
		// load settings
		var settings Settings

		settingsFile := filepath.Join(ProgramInfo.ExecDir, "gdep-settings.json")

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

		var javaHome string
		var cacheDir string

		if settings.JavaHome != nil {
			javaHome = *settings.JavaHome
		} else {
			javaHome = (os.Getenv("JAVA_HOME"))
		}

		if settings.CacheDir != nil {
			cacheDir = *settings.CacheDir
		} else {
			cacheDir = filepath.Join(ProgramInfo.ExecDir, "gdep-cache")
		}

		ProgramInfo.JavaHome = toAbsolute(javaHome)
		ProgramInfo.CacheDir = toAbsolute(cacheDir)
	}

	// create cache dir
	{
		err := os.MkdirAll(ProgramInfo.CacheDir, 0775)
		if err != nil {
			ErrLogger.Fatalf("failed to create directory \"%s\": %v", ProgramInfo.CacheDir, err)
		}
	}

	sourceCodes, err := GetExternalSourceCodes()
	if err != nil {
		ErrLogger.Fatal(err)
	}

	for _, sc := range sourceCodes {
		fmt.Printf("===================\n")
		fmt.Printf("GetSourceDirPath : %s\n", sc.GetSourceDirPath())
		fmt.Printf("SourceFiles :\n")

		for _, file := range sc.GetSourceFiles() {
			fmt.Printf("    %s\n", file)
		}
		fmt.Printf("===================\n")
	}
}
