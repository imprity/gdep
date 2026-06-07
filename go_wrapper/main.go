package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"runtime/pprof"
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
	exitCode := (func() int {
		pprofFile, err := os.Create("cpu.pprof")
		if err != nil {
			ErrLogger.Fatal("could not create CPU profile: ", err)
		}
		defer pprofFile.Close()
		if err := pprof.StartCPUProfile(pprofFile); err != nil {
			ErrLogger.Fatal("could not start CPU profile: ", err)
		}
		defer pprof.StopCPUProfile()

		return AppMain()
	})()

	os.Exit(exitCode)
}

func AppMain() int {
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
	{
		wd, err := os.Getwd()
		if err != nil {
			ErrLogger.Fatalf("failed to get current working directory: %v", err)
		}
		ProgramInfo.Cwd = wd
	}

	// ExecDir
	{
		execPath, err := os.Executable()
		if err != nil {
			ErrLogger.Fatalf("failed to get executable's path: %v", err)
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

	args := os.Args[1:]

	// init commands
	commands := []Command{
		&DirsCommand{}, &FilesCommand{}, &PackCommand{},
	}

	// if no arguments were given, just print help and exit
	if len(args) <= 0 {
		PrintHelp(os.Stderr, commands)
		return 1
	}

	// user wants help
	if args[0] == "help" {
		PrintHelp(os.Stdout, commands)
	}

	var toRun Command

	for _, c := range commands {
		if c.GetName() == args[0] {
			toRun = c
			break
		}
	}

	if toRun == nil {
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", args[0])
		fmt.Fprintf(os.Stderr, "\n")
		PrintHelp(os.Stderr, commands)
		return 1
	}

	sourceCodes, err := GetExternalSourceCodes()
	if err != nil {
		ErrLogger.Fatal(err)
	}

	err = toRun.Run(sourceCodes, args[1:])
	if err != nil {
		ErrLogger.Fatal(err)
	}

	return 0
}

func PrintHelp(w io.Writer, commands []Command) {
	fmt.Fprint(w, "gdep\n")
	fmt.Fprint(w, "\n")
	fmt.Fprint(w, "usage:\n")
	fmt.Fprint(w, "\n")
	fmt.Fprint(w, "help : prints this message\n")
	for _, c := range commands {
		fmt.Fprintf(w, "%s : %s\n", c.GetName(), c.GetDescription())
	}
}
