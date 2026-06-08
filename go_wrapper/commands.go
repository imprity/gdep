package main

import (
	"fmt"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"flag"
	"os"
)

type Command interface {
	GetName() string
	GetDescription() string

	ParseArgs(args []string) error

	Run(sourceCodes []SourceCode) error
}

// ==============================
// Dir
// ==============================

type DirsCommand struct {
}

func (d *DirsCommand) GetName() string {
	return "dirs"
}

func (d *DirsCommand) GetDescription() string {
	return "list source directories"
}

func (d *DirsCommand) ParseArgs(args []string) error {
	return GetDefaultFlagSet(d).Parse(args)
}

func (d *DirsCommand) Run(sourceCodes []SourceCode) error {
	slices.SortFunc(sourceCodes, func(a, b SourceCode) int {
		return strings.Compare(a.GetSourceDirPath(), b.GetSourceDirPath())
	})

	for _, sc := range sourceCodes {
		fmt.Println(sc.GetSourceDirPath())
	}

	return nil
}

// ==============================
// Files
// ==============================

type FilesCommand struct {
}

func (f *FilesCommand) GetName() string {
	return "files"
}

func (f *FilesCommand) GetDescription() string {
	return "list source files"
}

func (f *FilesCommand) ParseArgs(args []string) error {
	return GetDefaultFlagSet(f).Parse(args)
}

func (f *FilesCommand) Run(sourceCodes []SourceCode) error {
	srcFiles := make([]string, 0, 1024)

	for _, sc := range sourceCodes {
		srcFiles = append(srcFiles, sc.GetSourceFiles()...)
	}

	slices.Sort(srcFiles)

	for _, file := range srcFiles {
		fmt.Println(file)
	}

	return nil
}

// ==============================
// Pack
// ==============================

type PackCommand struct {
	ClassName string
	CandidateCount uint
}

func (p *PackCommand) GetName() string {
	return "pack"
}

func (p *PackCommand) GetDescription() string {
	return "search files using class path. e.g. gdep pack o.s.w.s.DispatcherServlet"
}

func (p *PackCommand) ParseArgs(args []string) error {
	flagset := GetDefaultFlagSet(p)

	flagset.UintVar(&p.CandidateCount, "n", 5, "number of candidates to print")

	if err:= flagset.Parse(args); err != nil {
		return err
	}

	args = flagset.Args()

	if len(args) <= 0 {
		fmt.Fprintf(os.Stderr, "pack command needs atleast one argument\n")
		flagset.Usage()
		return ErrExpected
	}
	p.ClassName = strings.TrimSpace(args[0])

	return nil
}

func (p *PackCommand) Run(sourceCodes []SourceCode) error {
	type pathAndScore struct {
		Dir   string
		Path  string
		Score int
	}

	pathAndScores := make([]pathAndScore, 0, 1024)

	for _, sc := range sourceCodes {
		for _, file := range sc.GetSourceFiles() {
			pathAndScores = append(pathAndScores, pathAndScore{
				Dir:   sc.GetSourceDirPath(),
				Path:  file,
				Score: 0,
			})
		}
	}

	cursor := 0
	batchSize := 100

	var wg sync.WaitGroup

	for cursor < len(pathAndScores) {
		begin := cursor
		end := min(cursor+batchSize, len(pathAndScores))
		wg.Add(1)

		go func() {
			defer wg.Done()

			mat := NewMatrix(512)

			for i := begin; i < end; i++ {
				scoreFileName := pathAndScores[i].Path
				if rel, relErr := filepath.Rel(pathAndScores[i].Dir, scoreFileName); relErr == nil {
					scoreFileName = rel
				}

				score := ScoreClassNameSimilarity(scoreFileName, p.ClassName, mat)
				pathAndScores[i].Score = score
			}
		}()

		cursor += batchSize
	}

	wg.Wait()

	slices.SortFunc(pathAndScores, func(a, b pathAndScore) int {
		if a.Score > b.Score {
			return -1
		} else if a.Score < b.Score {
			return 1
		} else {
			return 0
		}
	})

	for i := 0; i < min(len(pathAndScores), int(p.CandidateCount)); i++ {
		ps := pathAndScores[i]
		fmt.Println(ps.Path)
	}

	return nil
}

// ==============================
// Helper Functions
// ==============================

func GetDefaultFlagSet(cmd Command) *flag.FlagSet{
	flagset := flag.NewFlagSet(cmd.GetName(), flag.ExitOnError)

	flagset.Usage = func() {
		fmt.Fprintf(os.Stderr, "Command %s:\n", cmd.GetName())
		fmt.Fprintf(os.Stderr, "  %s\n", cmd.GetDescription())
		flagset.PrintDefaults()
	}

	return flagset;
}
