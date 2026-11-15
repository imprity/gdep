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

type MavenInfo struct {
	ExternalSourceJars       []string `json:"externalSourceJars"`
	ProjectSourceDirectories []string `json:"projectSourceDirectories"`
}

type CachedMavenInfo struct {
	MavenInfo

	FingerPrintFiles []string
	FingerPrintHash  string
	ExpiresAt        time.Time
}

func GetMavenInfo(projectDir string, ignoreCache bool) (MavenInfo, error) {
	// first collect fingerprint hash files
	fingerPrintFiles, err := GetMavenFingerPrintFiles(projectDir)
	if err != nil {
		return MavenInfo{}, fmt.Errorf("failed to collect finger print files %w", err)
	}

	// create hash based on finger print files
	hash, err := GetFilesFingerPrintHash(fingerPrintFiles)
	if err != nil {
		return MavenInfo{}, fmt.Errorf("failed to hash finger print files %w", err)
	}

	// check cached json file of MavenInfo
	jsonCachePath := GetJsonMavenCachePath(hash)

	if !ignoreCache {
		exists, err := RegularFileExists(jsonCachePath)
		if err != nil {
			return MavenInfo{}, err
		}

		if exists { // cached json file exists, try to use it
			cachedInfo, cacheLoadErr := LoadMavenInfoFromJsonCache(jsonCachePath)

			if cacheLoadErr != nil {
				ErrLogger.Printf("failed to load cached Maven info from \"%s\": %v", jsonCachePath, cacheLoadErr)
				ErrLogger.Printf("trying to get from maven directly")
			} else if cachedInfo.FingerPrintHash == hash && time.Now().Before(cachedInfo.ExpiresAt) {
				return cachedInfo.MavenInfo, nil
			}
		}
	}

	// either cahce expired or something went wrong
	// get directly from java
	info, err := GetMavenInfoFromJava(projectDir)
	if err != nil {
		return MavenInfo{}, fmt.Errorf("failed to get Maven info from java: %w", err)
	}

	// save it to json cache
	expiresAt := time.Now().Add(ToolingApiCacheTTL)

	cachedInfo := CachedMavenInfo{
		MavenInfo: info,

		FingerPrintFiles: fingerPrintFiles,
		FingerPrintHash:  hash,

		ExpiresAt: expiresAt,
	}

	jsonBytes, err := json.MarshalIndent(cachedInfo, "", "  ")
	if err != nil {
		return MavenInfo{}, err
	}

	err = os.WriteFile(jsonCachePath, jsonBytes, 0664)
	if err != nil {
		return MavenInfo{}, err
	}

	return info, nil
}

func LoadMavenInfoFromJsonCache(jsonCachePath string) (CachedMavenInfo, error) {
	jsonBytes, err := os.ReadFile(jsonCachePath)
	if err != nil {
		return CachedMavenInfo{}, err
	}

	var cachedInfo CachedMavenInfo
	err = json.Unmarshal(jsonBytes, &cachedInfo)
	if err != nil {
		return CachedMavenInfo{}, err
	}

	return cachedInfo, nil
}

func GetMavenInfoFromJava(projectDir string) (MavenInfo, error) {
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
		"-Dgdep.internal.project.type=maven",
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
		return MavenInfo{}, fmt.Errorf("failed to get Gradle Tooling API info from java: %w", err)
	}

	var info MavenInfo
	err = json.Unmarshal(buf.Bytes(), &info)
	if err != nil {
		return MavenInfo{}, fmt.Errorf("failed to get Gradle Tooling API info from java: %w", err)
	}

	return info, nil
}

// ============================
// helper fucntions
// ============================

func GetJsonMavenCachePath(fingerPrintHash string) string {
	fileName := fingerPrintHash + ".maven-cache.json"

	return filepath.Join(ProgramInfo.CacheDir, fileName)
}

var mavenFingerPrintFilePatterns = []string{
	"**/pom.xml",
	"pom.xml",
	".mvn/wrapper/maven-wrapper.properties",
	".mvn/extensions.xml",
	".mvn/maven.config",
	".mvn/jvm.config",
}

var mavenFingerPrintFilePatternGlobs []glob.Glob = nil

func GetMavenFingerPrintFiles(projectDir string) ([]string, error) {
	if mavenFingerPrintFilePatternGlobs == nil {
		for _, p := range mavenFingerPrintFilePatterns {
			g := glob.MustCompile(p, '/')
			mavenFingerPrintFilePatternGlobs = append(mavenFingerPrintFilePatternGlobs, g)
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

		for _, g := range mavenFingerPrintFilePatternGlobs {
			if g.Match(rel) {
				fingerPrintFiles = append(fingerPrintFiles, file)
				break
			}
		}
	}

	return fingerPrintFiles, nil
}

// if these files exist, it's either a maven project
// or a maven sub project
var mavenMarkers = []string{
	"pom.xml",
}

// walks up from current directory to maven project dir
// returns projectDir, whether or not it's a maven project, and an error if any
func WalkUpToMavenProjectDir() (string, bool, error) {
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

			for _, marker := range mavenMarkers {
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
