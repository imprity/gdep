package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
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

var ErrLogger = log.New(os.Stderr, "[ERROR]: ", log.Lshortfile)
var InfoLogger = log.New(os.Stderr, "[INFO]: ", log.Lshortfile)

var ErrExpected = errors.New("expected error, nothing to do but to exit")

// TODO: maybe check java version

func main() {
	err := AppMain()

	if err != nil {
		if !errors.Is(err, ErrExpected) {
			ErrLogger.Println(err)
		}
		os.Exit(1)
	}
	os.Exit(0)
}

func AppMain() error {
	args := os.Args[1:]

	// init commands
	commands := []Command{
		&DirsCommand{},
		&FilesCommand{},
		&PackCommand{},
	}

	// init flagset
	flagset := flag.NewFlagSet("main", flag.ExitOnError)

	// ====================
	// FLAGS
	// ====================
	var pprofFileName string
	var ignoreCache bool

	flagset.StringVar(&pprofFileName, "pprof", "", "write cpu profile to given file")
	flagset.BoolVar(&ignoreCache, "ignore-cache", false, "ignore Gradle Tooling API cache")
	// ====================

	flagset.Usage = getFlagUsageFunc(commands, flagset)

	// we ignore the errors because flagset will exit anyway
	flagset.Parse(args)

	// get the rest of the arguments
	args = flagset.Args()

	// if no arguments were given, just print help and exit
	if len(args) <= 0 {
		flagset.Usage()
		return ErrExpected
	}

	// user wants help
	if args[0] == "help" {
		flagset.Usage()
		return nil
	}

	// get command user wants to run
	var toRun Command

	for _, c := range commands {
		if c.GetName() == args[0] {
			toRun = c
			break
		}
	}

	// unknown command, exit
	if toRun == nil {
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", args[0])
		fmt.Fprintf(os.Stderr, "\n")
		flagset.Usage()
		return ErrExpected
	}

	// consume argument
	args = args[1:]

	if err := toRun.ParseArgs(args); err != nil {
		return fmt.Errorf("failed to parse arguments for %s command: %w", toRun.GetName(), err)
	}

	pprofFileName = strings.TrimSpace(pprofFileName)
	if pprofFileName != "" {
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
			javaHome = os.Getenv("JAVA_HOME")
		}

		if settings.CacheDir != nil {
			cacheDir = *settings.CacheDir
		} else {
			cacheDir = filepath.Join(ProgramInfo.ExecDir, "gdep-cache")
		}

		if strings.TrimSpace(javaHome) == "" {
			return fmt.Errorf("JAVA_HOME is not set")
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

	sourceCodes, err := GetSourceCodes(ignoreCache)
	if err != nil {
		return fmt.Errorf("failed to get source codes: %w", err)
	}

	err = toRun.Run(sourceCodes)
	if err != nil {
		return fmt.Errorf("failed to execute command \"%s\": %w", toRun.GetName(), err)
	}

	return nil
}

func getFlagUsageFunc(commands []Command, flagSet *flag.FlagSet) func() {
	return func() {
		w := os.Stderr

		fmt.Fprint(w, "gdep\n")
		fmt.Fprint(w, "\n")
		fmt.Fprint(w, "usage:\n")
		fmt.Fprint(w, "\n")
		fmt.Fprint(w, "flags:\n")
		flagSet.PrintDefaults()
		fmt.Fprint(w, "commands:\n")
		fmt.Fprint(w, "  help : prints this message\n")
		for _, c := range commands {
			fmt.Fprintf(w, "  %s : %s\n", c.GetName(), c.GetDescription())
		}
		fmt.Fprint(w, "\n")
		fmt.Fprint(w, "example with flag:\n")
		fmt.Fprint(w, "  gdep --pprof=cpu.pprof pack o.s.w.s.DispatcherServlet\n")
	}
}
