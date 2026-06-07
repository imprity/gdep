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
	"strings"
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

var GracefulErr = errors.New("expected error, nothing to do but to exit")

// TODO: maybe check java version

func main() {
	err := AppMain()

	if err != nil {
		if !errors.Is(err, GracefulErr) {
			ErrLogger.Println(err)
		}
		os.Exit(1)
	}
	os.Exit(0)
}

func AppMain() error {
	args := os.Args[1:]

	// check if first argument is profiling argument
	if len(args) > 0 {
		if cut, didCut := strings.CutPrefix(args[0], "pprof="); didCut {
			pprofFileName := strings.TrimSpace(cut)
			pprofFile, err := os.Create(pprofFileName)
			if err != nil {
				return fmt.Errorf("could not create CPU profile \"%s\": %w", pprofFileName, err)
			}
			defer pprofFile.Close()

			if err := pprof.StartCPUProfile(pprofFile); err != nil {
				return fmt.Errorf("could not start CPU profile: %w", err)
			}
			defer pprof.StopCPUProfile()
		}
	}

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
			return fmt.Errorf("failed to get current working directory: %w", err)
		}
		ProgramInfo.Cwd = wd
	}

	// ExecDir
	{
		execPath, err := os.Executable()
		if err != nil {
			return fmt.Errorf("failed to get executable's path: %w", err)
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
				return fmt.Errorf("could not open settings at \"%s\": %w", settingsFile, err)
			}
		} else {
			err := json.Unmarshal(settingsJson, &settings)
			if err != nil {
				return fmt.Errorf("failed to parse \"%s\": %w", settingsFile, err)
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
			return fmt.Errorf("failed to create directory \"%s\": %w", ProgramInfo.CacheDir, err)
		}
	}

	// init commands
	commands := []Command{
		&DirsCommand{}, &FilesCommand{}, &PackCommand{},
	}

	// if no arguments were given, just print help and exit
	if len(args) <= 0 {
		PrintHelp(os.Stderr, commands)
		return GracefulErr
	}

	// user wants help
	if args[0] == "help" {
		PrintHelp(os.Stdout, commands)
		return nil
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
		return GracefulErr
	}

	sourceCodes, err := GetSourceCodes()
	if err != nil {
		return fmt.Errorf("failed to get source codes: %w", err)
	}

	err = toRun.Run(sourceCodes, args[1:])
	if err != nil {
		return fmt.Errorf("failed to execute command \"%s\": %w", toRun.GetName(), err)
	}

	return nil
}

func PrintHelp(w io.Writer, commands []Command) {
	fmt.Fprint(w, "gdep\n")
	fmt.Fprint(w, "\n")
	fmt.Fprint(w, "usage:\n")
	fmt.Fprint(w, "  flags:\n")
	fmt.Fprint(w, "    pprof=<file> : write cpu profile to <file>\n")
	fmt.Fprint(w, "\n")
	fmt.Fprint(w, "  commands:\n")
	fmt.Fprint(w, "    help : prints this message\n")
	for _, c := range commands {
		fmt.Fprintf(w, "    %s : %s\n", c.GetName(), c.GetDescription())
	}
	fmt.Fprint(w, "\n")
	fmt.Fprint(w, "  example with flag:\n")
	fmt.Fprint(w, "    gdep pprof=cpu.pprof pack o.s.w.s.DispatcherServlet\n")
}
