package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"
)

var ToolingApiCacheTTL = time.Hour * 3

type SourceCode interface {
	GetSourceDirPath() string
	GetSourceFiles() []string
}

type ExternalSourceCode struct {
	SourceDirPath string
	SourceFiles   []string

	SourceJarPath string
	SourceJarHash string

	KnownCacheDir string
}

func (es *ExternalSourceCode) GetSourceDirPath() string {
	return es.SourceDirPath
}

func (es *ExternalSourceCode) GetSourceFiles() []string {
	return es.SourceFiles
}

type ProjectSourceCode struct {
	SourceDirPath string
	SourceFiles   []string
}

func (ps *ProjectSourceCode) GetSourceDirPath() string {
	return ps.SourceDirPath
}

func (ps *ProjectSourceCode) GetSourceFiles() []string {
	return ps.SourceFiles
}

func GetSourceCodes(ignoreCache bool) ([]SourceCode, error) {
	var gradleProjectDir string
	var mavenProjectDir string

	var inGradleProject bool
	var inMavenProject bool

	var err error

	// try to get project directory
	gradleProjectDir, inGradleProject, err = WalkUpToGradleProjectDir()
	if err != nil {
		return nil, err
	}

	mavenProjectDir, inMavenProject, err = WalkUpToMavenProjectDir()
	if err != nil {
		return nil, err
	}

	if inGradleProject { // we are in gradle project
		info, err := GetGradleToolingInfo(gradleProjectDir, ignoreCache)
		if err != nil {
			return nil, err
		}

		var sourceCodes []SourceCode

		// get SourceCode from externSourceJarPaths
		for _, jarPath := range info.ExternalSourceJars {
			sc, err := GetSourceCodeFromJar(jarPath)
			if err != nil {
				return nil, err
			}
			sourceCodes = append(sourceCodes, sc)
		}

		// get SourceCode from jdk (if it exists)
		for _, jdkPath := range info.JdkPaths {
			jdkZipPath := filepath.Join(filepath.Clean(jdkPath), "lib", "src.zip")
			jarSourceCode, err := GetSourceCodeFromJar(jdkZipPath)

			if err != nil {
				ErrLogger.Println(err)
			} else {
				sourceCodes = append(sourceCodes, jarSourceCode)
			}
		}

		// get SourceCode from projectSourceDirs
		for _, srcDir := range info.ProjectSourceDirectories {
			files, err := GetFilesInDirectory(srcDir)
			if err != nil {
				ErrLogger.Printf("failed to get files in \"%s\": %v", srcDir, err)
				continue
			}

			sourceCodes = append(sourceCodes, &ProjectSourceCode{
				SourceDirPath: srcDir,
				SourceFiles:   files,
			})
		}

		return sourceCodes, nil
	} else if inMavenProject {
		info, err := GetMavenInfo(mavenProjectDir, ignoreCache)
		if err != nil {
			return nil, err
		}

		var sourceCodes []SourceCode

		// get SourceCode from externSourceJarPaths
		for _, jarPath := range info.ExternalSourceJars {
			sc, err := GetSourceCodeFromJar(jarPath)
			if err != nil {
				return nil, err
			}
			sourceCodes = append(sourceCodes, sc)
		}

		// get SourceCode from jdk (if it exists)
		//
		// if project doesn't use toolchains, maven uses JAVA_HOME
		// so we fall back to that
		jdkPaths := info.JdkPaths
		if len(jdkPaths) == 0 {
			jdkPaths = []string{ProgramInfo.JavaHome}
		}

		for _, jdkPath := range jdkPaths {
			jdkZipPath := filepath.Join(filepath.Clean(jdkPath), "lib", "src.zip")
			jarSourceCode, err := GetSourceCodeFromJar(jdkZipPath)

			if err != nil {
				ErrLogger.Println(err)
			} else {
				sourceCodes = append(sourceCodes, jarSourceCode)
			}
		}

		// get SourceCode from projectSourceDirs
		for _, srcDir := range info.ProjectSourceDirectories {
			files, err := GetFilesInDirectory(srcDir)
			if err != nil {
				ErrLogger.Printf("failed to get files in \"%s\": %v", srcDir, err)
				continue
			}

			sourceCodes = append(sourceCodes, &ProjectSourceCode{
				SourceDirPath: srcDir,
				SourceFiles:   files,
			})
		}

		return sourceCodes, nil
	} else { // we are not in gradle, nor maven project
		// then just try to get zipped java src from jdk
		jdkZipPath := filepath.Join(ProgramInfo.JavaHome, "lib", "src.zip")
		jarSourceCode, err := GetSourceCodeFromJar(jdkZipPath)

		if err != nil {
			return nil, fmt.Errorf("failed to get jdk source code: %w", err)
		}

		return []SourceCode{jarSourceCode}, nil
	}
}

func GetSourceCodeFromJar(sourceJarPath string) (SourceCode, error) {
	// first get hash of the jar
	sourceJarHash, err := HashFileToString(sourceJarPath)
	if err != nil {
		return nil, err
	}

	// check cache
	jsonCachePath := GetJsonJarCachePath(sourceJarHash)

	var sourceCode ExternalSourceCode

	fileExists, err := RegularFileExists(jsonCachePath)
	if err != nil {
		return nil, err
	}

	if fileExists { // cache file exists, reusing cache
		sourceCode, err = LoadExternalSourceCodeFromCache(jsonCachePath)
		if err != nil {
			ErrLogger.Printf("failed to load cached source code: %s", err)
			ErrLogger.Printf("falling back to source jar at \"%s\"", sourceJarPath)

			if sourceCode, err = LoadExternalSourceCodeFromJar(sourceJarPath, sourceJarHash, jsonCachePath); err != nil {
				return nil, err
			}
		}
	} else {
		if sourceCode, err = LoadExternalSourceCodeFromJar(sourceJarPath, sourceJarHash, jsonCachePath); err != nil {
			return nil, err
		}
	}

	return &sourceCode, nil
}

func LoadExternalSourceCodeFromCache(jsonCachePath string) (ExternalSourceCode, error) {
	var sourceCode ExternalSourceCode

	jsonBytes, err := os.ReadFile(jsonCachePath)
	if err != nil {
		return ExternalSourceCode{}, err
	}

	err = json.Unmarshal(jsonBytes, &sourceCode)
	if err != nil {
		return ExternalSourceCode{}, err
	}

	// cached ExternalSourceCode's KnownCacheDir doesn't match with actual ProgramInfo.CacheDir.
	// meaning cache directory was moved after the cache was created.
	// so we need to 'repair' the entries in cached sourceCode
	// and resave it.
	if sourceCode.KnownCacheDir != ProgramInfo.CacheDir {
		newSourceFiles := make([]string, len(sourceCode.SourceFiles))

		for i, file := range sourceCode.SourceFiles {
			if !strings.HasPrefix(file, sourceCode.KnownCacheDir) {
				return ExternalSourceCode{}, fmt.Errorf(
					"cached file \"%s\" doesn't start with known cache dir \"%s\"",
					file, sourceCode.KnownCacheDir,
				)
			}

			relPath, err := filepath.Rel(sourceCode.KnownCacheDir, file)
			if err != nil {
				return ExternalSourceCode{}, err
			}

			newPath := filepath.Join(ProgramInfo.CacheDir, relPath)

			newSourceFiles[i] = newPath
		}

		// same with dirs
		var newSourceDirPath string
		{
			if !strings.HasPrefix(sourceCode.SourceDirPath, sourceCode.KnownCacheDir) {
				return ExternalSourceCode{}, fmt.Errorf(
					"cached dir \"%s\" doesn't start with known cache dir \"%s\"",
					sourceCode.SourceDirPath, sourceCode.KnownCacheDir,
				)
			}

			relPath, err := filepath.Rel(sourceCode.KnownCacheDir, sourceCode.SourceDirPath)
			if err != nil {
				return ExternalSourceCode{}, err
			}

			newSourceDirPath = filepath.Join(ProgramInfo.CacheDir, relPath)
		}

		// update source code
		sourceCode.SourceFiles = newSourceFiles
		sourceCode.SourceDirPath = newSourceDirPath
		sourceCode.KnownCacheDir = ProgramInfo.CacheDir

		// update cache in the file
		saveErrMsg := fmt.Sprintf("failed to update external source code to cache \"%s\"", jsonCachePath)
		jsonBytes, err := json.MarshalIndent(sourceCode, "", "  ")
		if err != nil {
			return ExternalSourceCode{}, fmt.Errorf("%s: %w", saveErrMsg, err)
		}

		err = os.WriteFile(jsonCachePath, jsonBytes, 0664)
		if err != nil {
			return ExternalSourceCode{}, fmt.Errorf("%s: %w", saveErrMsg, err)
		}
	}

	return sourceCode, nil
}

func LoadExternalSourceCodeFromJar(
	sourceJarPath,
	sourceJarHash,
	jsonCachePath string,
) (ExternalSourceCode, error) {
	sourceDirPath := GetUnzippedSourceDirPath(sourceJarPath, sourceJarHash)

	// if sourceDirPath already exists, we delete it
	{
		someFile, err := FileExists(sourceDirPath)

		if err != nil {
			return ExternalSourceCode{}, err
		}

		if someFile != nil {
			if !someFile.IsDir() {
				return ExternalSourceCode{}, fmt.Errorf("tried to unzip at \"%s\" but encountered a file. considered deleting it but something other than a directory exists there", sourceDirPath)
			}

			InfoLogger.Printf("trying to unzip source code at \"%s\" but a directory is already there. deleting it", sourceDirPath)

			err := os.RemoveAll(sourceDirPath)
			if err != nil {
				return ExternalSourceCode{}, err
			}
		}
	}

	// unzipping takes fucking forever
	// atleast log it
	InfoLogger.Printf("unzipping \"%s\" to \"%s\"", sourceJarPath, sourceDirPath)
	err := ExtractZip(sourceJarPath, sourceDirPath)
	if err != nil {
		return ExternalSourceCode{}, err
	}

	sourceFiles, err := GetFilesInDirectory(sourceDirPath)
	if err != nil {
		return ExternalSourceCode{}, err
	}

	var sourceCode ExternalSourceCode

	sourceCode = ExternalSourceCode{
		SourceDirPath: sourceDirPath,
		SourceFiles:   sourceFiles,
		SourceJarPath: sourceJarPath,
		SourceJarHash: sourceJarHash,

		KnownCacheDir: ProgramInfo.CacheDir,
	}

	jsonBytes, err := json.MarshalIndent(sourceCode, "", "  ")
	if err != nil {
		return ExternalSourceCode{}, err
	}

	err = os.WriteFile(jsonCachePath, jsonBytes, 0664)
	if err != nil {
		return ExternalSourceCode{}, err
	}

	return sourceCode, nil
}

// ============================
// helper fucntions
// ============================

func GetUnzippedSourceDirPath(sourceJarPath, sourceJarHash string) string {
	filename := filepath.Base(sourceJarPath)

	if cut, didCut := strings.CutSuffix(filename, "-sources.jar"); didCut {
		filename = cut
	} else if cut, didCut := strings.CutSuffix(filename, ".jar"); didCut {
		filename = cut
	} else if cut, didCut := strings.CutSuffix(filename, ".zip"); didCut {
		filename = cut
	}

	var dirName string

	if filename == "." || filename == "" {
		dirName = "zipped-src-" + sourceJarHash
	} else {
		dirName = filename + "-" + sourceJarHash
	}

	return filepath.Join(ProgramInfo.CacheDir, dirName)
}

func GetJsonJarCachePath(sourceJarHash string) string {
	fileName := sourceJarHash + ".jar-cache.json"

	return filepath.Join(ProgramInfo.CacheDir, fileName)
}

func GetFilesFingerPrintHash(fingerPrintFiles []string) (string, error) {
	hasher := sha256.New()

	for _, filename := range fingerPrintFiles {
		file, err := os.Open(filename)
		if err != nil {
			return "", err
		}
		defer file.Close()

		_, err = io.Copy(hasher, file)
		if err != nil {
			return "", err
		}
	}

	return hex.EncodeToString(hasher.Sum(nil)), nil
}
