package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"github.com/gobwas/glob"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

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

func GetGradleToolingInfo(projectDir string, ignoreCache bool) (GradleToolingInfo, error) {
	// first collect fingerprint hash files
	fingerPrintFiles, err := GetGradleFingerPrintFiles(projectDir)
	if err != nil {
		return GradleToolingInfo{}, fmt.Errorf("failed to collect finger print files %w", err)
	}

	// create hash based on finger print files
	hash, err := GetFilesFingerPrintHash(fingerPrintFiles)
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

func GetJsonToolingAPICachePath(fingerPrintHash string) string {
	fileName := fingerPrintHash + ".gradle-cache.json"

	return filepath.Join(ProgramInfo.CacheDir, fileName)
}

// list is copy pasted from
// https://github.com/gradle/actions/blob/main/sources/src/cache-service-basic.ts
var gradleFingerPrintFilePatterns = []string{
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

var gradleFingerPrintFilePatternGlobs []glob.Glob = nil

func GetGradleFingerPrintFiles(projectDir string) ([]string, error) {
	if gradleFingerPrintFilePatternGlobs == nil {
		for _, p := range gradleFingerPrintFilePatterns {
			g := glob.MustCompile(p, '/')
			gradleFingerPrintFilePatternGlobs = append(gradleFingerPrintFilePatternGlobs, g)
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

		for _, g := range gradleFingerPrintFilePatternGlobs {
			if g.Match(rel) {
				fingerPrintFiles = append(fingerPrintFiles, file)
				break
			}
		}
	}

	return fingerPrintFiles, nil
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
// returns projectDir, whether or not it's a gradle project, and an error if any
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
