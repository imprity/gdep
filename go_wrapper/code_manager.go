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

type GradleToolingInfo struct {
	ExternalSourceJars       []string `json:"externalSourceJars"`
	ProjectSourceDirectories []string `json:"projectSourceDirectories"`
	JdkPaths                 []string `json:"jdkPaths"`
}

type CachedGradleToolingInfo struct {
	GradleToolingInfo

	FingerPrintFiles []string
	FingerPrintHash  string
	ExpiresAt        time.Time
}

func GetSourceCodes(ignoreCache bool) ([]SourceCode, error) {
	var projectDir string
	var inGradleProject bool
	var err error

	// try to get project directory
	projectDir, inGradleProject, err = WalkUpToGradleProjectDir()
	if err != nil {
		return nil, err
	}

	if inGradleProject { // we are in gradle project
		info, err := GetGradleToolingInfo(projectDir, ignoreCache)
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
	} else { // we are not in gradle project
		// then just try to get zipped java src from jdk
		jdkZipPath := filepath.Join(ProgramInfo.JavaHome, "lib", "src.zip")
		jarSourceCode, err := GetSourceCodeFromJar(jdkZipPath)

		if err != nil {
			return nil, fmt.Errorf("failed to get jdk source code: %w", err)
		}

		return []SourceCode{jarSourceCode}, nil
	}
}

func GetGradleToolingInfo(projectDir string, ignoreCache bool) (GradleToolingInfo, error) {
	// first collect fingerprint hash files
	fingerPrintFiles, err := GetFingerPrintFiles(projectDir)
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

	if !ignoreCache {
		exists, err := RegularFileExists(jsonCachePath)
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
	}

	// either cahce expired or something went wrong
	// get directly from java
	info, err := GetGradleToolingInfoFromJava(projectDir)
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

func GetGradleToolingInfoFromJava(projectDir string) (GradleToolingInfo, error) {
	var args []string

	javaExe := filepath.Join(ProgramInfo.JavaHome, "bin", "java")
	gdepJarPath := filepath.Join(ProgramInfo.ExecDir, "gdep.jar")

	// read about --enable-native-access=ALL-UNNAMED
	// here: https://teamdev.com/jxbrowser/blog/native-access-restrictions-in-java-24/
	if ProgramInfo.JavaVersion >= 24 {
		args = append(
			args,
			"--enable-native-access=ALL-UNNAMED",
		)
	}

	args = append(
		args,
		fmt.Sprintf("-Dgdep.internal.project.dir=%s", projectDir),
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
		return GradleToolingInfo{}, fmt.Errorf("failed to get Gradle Tooling API info from java: %w", err)
	}

	var info GradleToolingInfo
	err = json.Unmarshal(buf.Bytes(), &info)
	if err != nil {
		return GradleToolingInfo{}, fmt.Errorf("failed to get Gradle Tooling API info from java: %w", err)
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

func GetFingerPrintFiles(projectDir string) ([]string, error) {
	if fingerPrintFilePatternGlobs == nil {
		for _, p := range fingerPrintFilePatterns {
			g := glob.MustCompile(p, '/')
			fingerPrintFilePatternGlobs = append(fingerPrintFilePatternGlobs, g)
		}
	}

	files, err := GetFilesInDirectory(projectDir)
	if err != nil {
		return nil, err
	}

	var fingerPrintFiles []string

	for _, file := range files {
		rel, err := filepath.Rel(projectDir, file)
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

// if these files exist, it's either a gradle project
// or a gradle sub project
var gradleMarkers = []string{
	"build.gradle",
	"build.gradle.kts",
	"settings.gradle",
	"settings.gradle.kts",
}

// walks up from current directory to gradle project dir
func WalkUpToGradleProjectDir() (string, bool, error) {
	dir := ProgramInfo.Cwd

	for {
		entries, err := os.ReadDir(dir)
		if err != nil {
			return "", false, fmt.Errorf("failed to walk up the directory: %w", err)
		}

		for _, entry := range entries {
			if !entry.Type().IsRegular() {
				continue
			}

			for _, marker := range gradleMarkers {
				if entry.Name() == marker {
					return dir, true, nil
				}
			}
		}

		// walk up
		walkedUp := filepath.Dir(dir)

		if walkedUp == dir ||
			walkedUp == "." ||
			walkedUp == "" {
			break
		}

		dir = walkedUp
	}

	return "", false, nil
}
