package main

import (
	"fmt"
	"path/filepath"
	"slices"
	"strings"
	"sync"
)

type Command interface {
	GetName() string
	GetDescription() string

	Run(sourceCodes []SourceCode, args []string) error
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

func (d *DirsCommand) Run(sourceCodes []SourceCode, args []string) error {
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

func (f *FilesCommand) Run(sourceCodes []SourceCode, args []string) error {
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
}

func (p *PackCommand) GetName() string {
	return "pack"
}

func (p *PackCommand) GetDescription() string {
	return "search files using class path. e.g. gdep pack o.s.w.s.DispatcherServlet"
}

func (p *PackCommand) Run(sourceCodes []SourceCode, args []string) error {
	if len(args) <= 0 {
		return fmt.Errorf("pack command needs atleast one argument")
	}

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

				score := ScoreClassNameSimilarity(scoreFileName, args[0], mat)
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

	for i := 0; i < min(len(pathAndScores), 5); i++ {
		ps := pathAndScores[i]
		fmt.Println(ps.Path)
	}

	return nil
}
