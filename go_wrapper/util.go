package main

import (
	"archive/zip"
	"encoding/hex"
	"errors"
	"fmt"
	"go.dw1.io/rapidhash"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

func RegularFileExists(name string) (bool, error) {
	info, err := os.Stat(name)

	if err == nil {
		if info.Mode().IsRegular() {
			return true, nil
		} else {
			return false, nil
		}
	} else if errors.Is(err, os.ErrNotExist) {
		return false, nil
	} else {
		return false, err
	}
}

func DirExists(name string) (bool, error) {
	info, err := os.Stat(name)

	if err == nil {
		if info.Mode().IsDir() {
			return true, nil
		} else {
			return false, nil
		}
	} else if errors.Is(err, os.ErrNotExist) {
		return false, nil
	} else {
		return false, err
	}
}

// Checks if file exists.
// It file exists, return a fs.FileInfo.
// Else, returns nil.
//
// Returns non nil error  if something went wrong while trying to check.
func FileExists(name string) (fs.FileInfo, error) {
	info, err := os.Stat(name)

	if err == nil {
		return info, nil
	} else if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	} else {
		return nil, err
	}
}

// returns file absolute paths in directory
func GetFilesInDirectory(dirName string) ([]string, error) {
	dirName = filepath.Clean(dirName)

	var files []string

	walkErr := filepath.WalkDir(dirName, func(name string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}

		if d.Type().IsRegular() {
			abs, err := filepath.Abs(name)
			if err != nil {
				return err
			}

			files = append(files, abs)
		}

		return nil
	})

	if walkErr != nil {
		return nil, walkErr
	}

	return files, nil
}

func ExtractZip(srcZipFile, dstDir string) error {
	srcZipFile = filepath.Clean(srcZipFile)
	dstDir = filepath.Clean(dstDir)

	if err := os.MkdirAll(dstDir, 0775); err != nil {
		return err
	}

	r, err := zip.OpenReader(srcZipFile)
	if err != nil {
		return err
	}
	defer r.Close()

	for _, f := range r.File {
		if err := extractEntry(f, dstDir); err != nil {
			return err
		}
	}
	return nil
}

func extractEntry(f *zip.File, dstDir string) error {
	// Prevent zip slip
	filePath := filepath.Join(dstDir, f.Name)
	if !strings.HasPrefix(filePath, filepath.Clean(dstDir)+string(os.PathSeparator)) {
		return fmt.Errorf("illegal file path: %s", filePath)
	}

	if f.FileInfo().IsDir() {
		return os.MkdirAll(filePath, 0775)
	}

	if err := os.MkdirAll(filepath.Dir(filePath), 0775); err != nil {
		return err
	}

	srcStream, err := f.Open()
	if err != nil {
		return err
	}
	defer srcStream.Close()

	dstStream, err := os.OpenFile(filePath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0664)
	if err != nil {
		return err
	}
	defer dstStream.Close()

	_, err = io.Copy(dstStream, srcStream)
	return err
}

func HashFile(filename string) ([]byte, error) {
	filename = filepath.Clean(filename)
	f, err := os.Open(filename)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	hasher := rapidhash.New()

	_, err = io.Copy(hasher, f)
	if err != nil {
		return nil, err
	}

	return hasher.Sum(nil), nil
}

func HashFileToString(filename string) (string, error) {
	hash, err := HashFile(filename)
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(hash), nil
}

func AbsInt(n int) int {
	if n < 0 {
		return n * -1
	} else {
		return n
	}
}
