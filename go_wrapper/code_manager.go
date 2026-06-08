package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"github.com/gobwas/glob"
	"io"
	"os"
	"os/exec"
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

type GradleToolingInfo struct {
	ExternalSourceJars       []string `json:"externalSourceJars"`
	ProjectSourceDirectories []string `json:"projectSourceDirectories"`
	JdkPath                  *string  `json:"jdkPath"`
}

type CachedGradleToolingInfo struct {
	GradleToolingInfo

	FingerPrintFiles []string
	FingerPrintHash  string
	ExpiresAt        time.Time
}

func GetSourceCodes() ([]SourceCode, error) {
	info, err := GetGradleToolingInfo()
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
	if info.JdkPath != nil {
		jdkZipPath := filepath.Join(filepath.Clean(*info.JdkPath), "lib", "src.zip")
		jarSourceCode, err := GetSourceCodeFromJar(jdkZipPath)

		if err != nil {
			ErrLogger.Println(err) // TODO: have a control to quite this down
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
}

func GetGradleToolingInfo() (GradleToolingInfo, error) {
	// first collect fingerprint hash files
	fingerPrintFiles, err := GetFingerPrintFiles()
	if err != nil {
		return GradleToolingInfo{}, fmt.Errorf("failed to collect finger print files %w", err)
	}

	// create hash based on finger print files
	hash, err := GetGradleFingerPrintHash(fingerPrintFiles)
	if err != nil {
		return GradleToolingInfo{}, fmt.Errorf("failed to hash finger print files %w", err)
	}

	// check cached json file of GradleToolingInfo
	jsonCachePath := GetJsonToolingAPICachePath(hash)

	exists, err := FileExists(jsonCachePath)
	if err != nil {
		return GradleToolingInfo{}, err
	}

	if exists { // cached json file exists, try to use it
		cachedInfo, cacheLoadErr := LoadGradleToolingInfoFromJsonCache(jsonCachePath)

		if cacheLoadErr != nil {
			ErrLogger.Printf("failed to load cached Gradle Tooling API info from \"%s\": %v", jsonCachePath, cacheLoadErr)
			ErrLogger.Printf("trying to get from gradle directly")
		} else if cachedInfo.FingerPrintHash == hash && time.Now().Before(cachedInfo.ExpiresAt) {
			return cachedInfo.GradleToolingInfo, nil
		}
	}

	// either cahce expired or something went wrong
	// get directly from java
	info, err := GetGradleToolingInfoFromJava()
	if err != nil {
		return GradleToolingInfo{}, fmt.Errorf("failed to get Gradle Tooling API info from java: %w", err)
	}

	// save it to json cache
	expiresAt := time.Now().Add(ToolingApiCacheTTL)

	cachedInfo := CachedGradleToolingInfo{
		GradleToolingInfo: info,

		FingerPrintFiles: fingerPrintFiles,
		FingerPrintHash:  hash,

		ExpiresAt: expiresAt,
	}

	jsonBytes, err := json.MarshalIndent(cachedInfo, "", "  ")
	if err != nil {
		return GradleToolingInfo{}, err
	}

	err = os.WriteFile(jsonCachePath, jsonBytes, 0664)
	if err != nil {
		return GradleToolingInfo{}, err
	}

	return info, nil
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

	fileExists, err := FileExists(jsonCachePath)
	if err != nil {
		return nil, err
	}

	if fileExists {
		jsonBytes, err := os.ReadFile(jsonCachePath)
		if err != nil {
			return nil, err
		}
		err = json.Unmarshal(jsonBytes, &sourceCode)
		if err != nil {
			return nil, err
		}
	} else {
		sourceDirPath := GetUnzippedSourceDirPath(sourceJarPath, sourceJarHash)

		// unzipping takes fucking forever
		// atleast log it
		InfoLogger.Printf("unzipping \"%s\" to \"%s\"", sourceJarPath, sourceDirPath)
		err = ExtractZip(sourceJarPath, sourceDirPath)
		if err != nil {
			return nil, err
		}

		sourceFiles, err := GetFilesInDirectory(sourceDirPath)
		if err != nil {
			return nil, err
		}

		sourceCode = ExternalSourceCode{
			SourceDirPath: sourceDirPath,
			SourceFiles:   sourceFiles,
			SourceJarPath: sourceJarPath,
			SourceJarHash: sourceJarHash,
		}

		jsonBytes, err := json.MarshalIndent(sourceCode, "", "  ")
		if err != nil {
			return nil, err
		}

		err = os.WriteFile(jsonCachePath, jsonBytes, 0664)
		if err != nil {
			return nil, err
		}
	}

	return &sourceCode, nil
}

func LoadGradleToolingInfoFromJsonCache(jsonCachePath string) (CachedGradleToolingInfo, error) {
	jsonBytes, err := os.ReadFile(jsonCachePath)
	if err != nil {
		return CachedGradleToolingInfo{}, err
	}

	var cachedInfo CachedGradleToolingInfo
	err = json.Unmarshal(jsonBytes, &cachedInfo)
	if err != nil {
		return CachedGradleToolingInfo{}, err
	}

	return cachedInfo, nil
}

func GetGradleToolingInfoFromJava() (GradleToolingInfo, error) {
	var args []string

	javaExe := filepath.Join(ProgramInfo.JavaHome, "bin", "java")
	gdepJarPath := filepath.Join(ProgramInfo.ExecDir, "gdep.jar")

	args = append(
		args,
		"--enable-native-access=ALL-UNNAMED",
		"-jar",
		gdepJarPath,
	)

	args = append(
		args,
		os.Args[1:]...,
	)

	buf := &bytes.Buffer{}

	cmd := exec.Command(
		javaExe,
		args...,
	)

	cmd.Stdout = buf
	cmd.Stderr = os.Stderr

	err := cmd.Run()
	if err != nil {
		return GradleToolingInfo{}, fmt.Errorf("failed to get GradleToolingInfo from java: %w", err)
	}

	var info GradleToolingInfo
	err = json.Unmarshal(buf.Bytes(), &info)
	if err != nil {
		return GradleToolingInfo{}, fmt.Errorf("failed to get GradleToolingInfo from java: %w", err)
	}

	return info, nil
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

func GetJsonToolingAPICachePath(fingerPrintHash string) string {
	fileName := fingerPrintHash + ".api-cache.json"

	return filepath.Join(ProgramInfo.CacheDir, fileName)
}

// list is copy pasted from
// https://github.com/gradle/actions/blob/main/sources/src/cache-service-basic.ts
var fingerPrintFilePatterns = []string{
	"**/*.gradle*",
	"*.gradle*",
	"**/gradle-wrapper.properties",
	"gradle-wrapper.properties",
	"buildSrc/**/Versions.kt",
	"buildSrc/**/Dependencies.kt",
	"gradle/*.versions.toml",
	"**/versions.properties",
	"versions.properties",
}

var fingerPrintFilePatternGlobs []glob.Glob = nil

func GetFingerPrintFiles() ([]string, error) {
	if fingerPrintFilePatternGlobs == nil {
		for _, p := range fingerPrintFilePatterns {
			g := glob.MustCompile(p, '/')
			fingerPrintFilePatternGlobs = append(fingerPrintFilePatternGlobs, g)
		}
	}

	files, err := GetFilesInDirectory(ProgramInfo.Cwd)
	if err != nil {
		return nil, err
	}

	var fingerPrintFiles []string

	for _, file := range files {
		rel, err := filepath.Rel(ProgramInfo.Cwd, file)
		if err != nil {
			return nil, err
		}

		rel = strings.ReplaceAll(rel, "\\", "/")

		for _, g := range fingerPrintFilePatternGlobs {
			if g.Match(rel) {
				fingerPrintFiles = append(fingerPrintFiles, file)
				break
			}
		}
	}

	return fingerPrintFiles, nil
}

func GetGradleFingerPrintHash(fingerPrintFiles []string) (string, error) {
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
