package com.gdep;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.UnsupportedMethodException;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CodeManager {
    private final String cwd;
    private final String cacheDir;

    private final Gson gson;

    static final Logger logger = LoggerFactory.getLogger(CodeManager.class);

    static final Duration TOOLING_API_CACHE_TTL = Duration.ofHours(3);

    // ============================
    // SourceCode implementations
    // ============================

    private static record ExternalSourceCode(
            String sourceDirPath, List<String> sourceFiles, String sourceJarPath, String sourceJarHash)
            implements SourceCode {}

    private static record ProjectSourceCode(String sourceDirPath, List<String> sourceFiles) implements SourceCode {}

    // ============================

    // ============================
    // Gradle Tooling outputs
    // ============================

    private static record GradleToolingInfo(
            Set<String> externalSourceJars, Set<String> projectSourceDirectories, String jdkPath) {}

    private static record CachedGradleToolingInfo(
            GradleToolingInfo gradleToolingInfo,
            Set<String> fingerPrintFiles,
            String fingerPrintHash,
            Instant expiresAt) {}

    public CodeManager(String cwd, String cacheDir) {
        this.cwd = cwd;
        this.cacheDir = cacheDir;

        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    // ============================

    public List<SourceCode> getExternalSourceCodes() throws IOException {
        GradleToolingInfo info = getGradleToolingInfo();

        List<SourceCode> sourceCodes = new ArrayList<SourceCode>();

        // get SourceCode from externSourceJarPaths
        for (final String jarPath : info.externalSourceJars()) {
            sourceCodes.add(getSourceCodeFromJar(jarPath));
        }

        // get SourceCode from jdk (if it exists)
        if (info.jdkPath() != null) {
            Path jdkZipPath = Path.of(info.jdkPath(), "lib", "src.zip");
            try {
                sourceCodes.add(getSourceCodeFromJar(jdkZipPath.toString()));
            } catch (Exception e) {
                logger.error("failed to get jdk sources from {}", jdkZipPath, e);
            }
        }

        // get SourceCode from projectSourceDirs
        for (final String srcDir : info.projectSourceDirectories()) {
            List<String> files = Util.getFilesInDirectory(srcDir);

            sourceCodes.add(new ProjectSourceCode(srcDir, files));
        }

        return sourceCodes;
    }

    private SourceCode getSourceCodeFromJar(String sourceJarPath) throws IOException {
        // first get hash of the jar
        String sourceJarHash = Util.hashFileToString(sourceJarPath);

        // check cache
        Path jsonCachePath = Path.of(getJsonJarCachePath(sourceJarHash));

        ExternalSourceCode sourceCode;

        if (Files.exists(jsonCachePath) && Files.isRegularFile(jsonCachePath)) {
            // if cache exists, we just parse json cache
            String jsonString = Files.readString(jsonCachePath);
            sourceCode = gson.fromJson(jsonString, ExternalSourceCode.class);
        } else {
            // if it doesn't exist in cache, create a cache
            String sourceDirPath = getUnzippedSourceDirPath(sourceJarPath, sourceJarHash);

            Util.extractZip(sourceJarPath, sourceDirPath);

            List<String> sourceFiles = Util.getFilesInDirectory(sourceDirPath);

            sourceCode = new ExternalSourceCode(sourceDirPath, sourceFiles, sourceJarPath, sourceJarHash);

            String jsonString = gson.toJson(sourceCode);

            Files.writeString(jsonCachePath, jsonString, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        }

        return sourceCode;
    }

    private GradleToolingInfo getGradleToolingInfo() throws IOException {
        // first collect fingerprint hash
        Set<String> fingerPrintFiles = getFingerPrintFiles();

        String hash = getGradleFingerPrintHash(fingerPrintFiles);

        // check cache
        Path jsonCachePath = Path.of(getJsonToolingAPICachePath(hash));

        try {
            if (Files.exists(jsonCachePath) && Files.isRegularFile(jsonCachePath)) {
                String jsonString = Files.readString(jsonCachePath);
                CachedGradleToolingInfo cachedInfo = gson.fromJson(jsonString, CachedGradleToolingInfo.class);

                // only if fingerPrintHash matches and the cache is not expired yet
                if (cachedInfo.fingerPrintHash().equals(hash) && Instant.now().isBefore(cachedInfo.expiresAt())) {
                    return cachedInfo.gradleToolingInfo();
                }
            }
        } catch (Exception e) {
            logger.error("could not load cache from {}", jsonCachePath, e);
        }

        Set<String> externSourceJarPaths = new HashSet<>();

        String jdkPath = null;

        Set<String> projectSourceDirs = new HashSet<>();

        try (ProjectConnection connection = GradleConnector.newConnector()
                .forProjectDirectory(new File(cwd))
                .connect()) {

            EclipseProject project = connection.getModel(EclipseProject.class);

            // get external source jar paths
            Set<EclipseExternalDependency> deps = getProjectDependencies(project);
            for (final EclipseExternalDependency dep : deps) {
                File sourceFile = dep.getSource();

                if (sourceFile != null) {
                    externSourceJarPaths.add(sourceFile.getCanonicalPath());
                }
            }

            // try to get jdk path
            // this is technically wrong since each project could have
            // different jdk versions
            //
            // but I don't think it'd matter too much
            //
            // but the correct thing would be to collect java home for every projects
            try {
                var javaSettings = project.getJavaSourceSettings();
                if (javaSettings != null) {
                    jdkPath = javaSettings.getJdk().getJavaHome().getPath();
                }
            } catch (UnsupportedMethodException e) {
                logger.error("failed to get jdk path", e);
            }

            // get project source directories
            // TODO: implement include and exclude patterns if you can
            Set<EclipseSourceDirectory> srcDirs = getProjectSourceDirs(project);
            for (final EclipseSourceDirectory dir : srcDirs) {
                projectSourceDirs.add(Path.of(dir.getPath()).toAbsolutePath().toString());
            }
        }

        GradleToolingInfo info = new GradleToolingInfo(externSourceJarPaths, projectSourceDirs, jdkPath);

        Instant expiresAt = Instant.now().plus(TOOLING_API_CACHE_TTL);

        CachedGradleToolingInfo cachedInfo = new CachedGradleToolingInfo(info, fingerPrintFiles, hash, expiresAt);

        String jsonString = gson.toJson(cachedInfo);

        Files.writeString(jsonCachePath, jsonString, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);

        return info;
    }

    // ============================
    // helper fucntions
    // ============================
    private String getUnzippedSourceDirPath(String sourceJarPath, String sourceJarHash) {
        if (sourceJarPath.endsWith("-sources.jar")) {
            sourceJarPath = sourceJarPath.substring(0, sourceJarPath.length() - "-sources.jar".length());
        } else if (sourceJarPath.endsWith(".jar")) {
            sourceJarPath = sourceJarPath.substring(0, sourceJarPath.length() - ".jar".length());
        } else if (sourceJarPath.endsWith(".zip")) {
            sourceJarPath = sourceJarPath.substring(0, sourceJarPath.length() - ".zip".length());
        }

        String fileName = Path.of(sourceJarPath).getFileName().toString();

        String dirName = fileName + "-" + sourceJarHash;

        return Path.of(this.cacheDir, dirName).toString();
    }

    private String getJsonJarCachePath(String sourceJarHash) {
        String fileName = sourceJarHash + ".jar-cache.json";

        return Path.of(this.cacheDir, fileName).toString();
    }

    private String getJsonToolingAPICachePath(String projectDirectoryPath) {
        MessageDigest digest = null;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        digest.update(projectDirectoryPath.getBytes());
        String hash = HexFormat.of().formatHex(digest.digest());

        String fileName = hash + ".api-cache.json";

        return Path.of(this.cacheDir, fileName).toString();
    }

    private static Set<EclipseExternalDependency> getProjectDependencies(EclipseProject project) {
        Set<EclipseExternalDependency> deps = new HashSet<>();
        getProjectDependenciesImpl(deps, project);

        return deps;
    }

    private static void getProjectDependenciesImpl(Set<EclipseExternalDependency> deps, EclipseProject project) {
        DomainObjectSet<? extends EclipseExternalDependency> projectDeps = project.getClasspath();

        deps.addAll(projectDeps);

        DomainObjectSet<? extends EclipseProject> children = project.getChildren();

        for (final EclipseProject child : children) {
            getProjectDependenciesImpl(deps, child);
        }
    }

    private static Set<EclipseSourceDirectory> getProjectSourceDirs(EclipseProject project) {
        Set<EclipseSourceDirectory> deps = new HashSet<>();
        getProjectSourceDirsImpl(deps, project);

        return deps;
    }

    private static void getProjectSourceDirsImpl(Set<EclipseSourceDirectory> deps, EclipseProject project) {
        DomainObjectSet<? extends EclipseSourceDirectory> srcDirs = project.getSourceDirectories();

        deps.addAll(srcDirs);

        DomainObjectSet<? extends EclipseProject> children = project.getChildren();

        for (final EclipseProject child : children) {
            getProjectSourceDirsImpl(deps, child);
        }
    }

    private String getGradleFingerPrintHash(Set<String> fingerPrintFiles) throws IOException {
        // sort the files
        List<String> sortedFiles = fingerPrintFiles.stream().sorted().toList();

        MessageDigest digest = Util.getSha256Digest();

        for (String file : sortedFiles) {
            try (var dis = new DigestInputStream(new BufferedInputStream(new FileInputStream(file)), digest)) {
                dis.transferTo(OutputStream.nullOutputStream());
            } catch (Exception e) {
                logger.error("failed open file \"{}\" to caculcate finger printing hash", file, e);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    // list is copy pasted from
    // https://github.com/gradle/actions/blob/main/sources/src/cache-service-basic.ts
    private static Set<String> fingerPrintFilePatterns = Set.of(
            "glob:**/*.gradle*",
            "glob:**/gradle-wrapper.properties",
            "glob:buildSrc/**/Versions.kt",
            "glob:buildSrc/**/Dependencies.kt",
            "glob:gradle/*.versions.toml",
            "glob:**/versions.properties");

    private Set<String> getFingerPrintFiles() throws IOException {
        List<PathMatcher> matchers = fingerPrintFilePatterns.stream()
                .map(x -> FileSystems.getDefault().getPathMatcher(x))
                .toList();

        Set<String> toReturn = new HashSet<>();

        try (Stream<Path> direntStream = Files.walk(Path.of(cwd))) {
            List<Path> dirents = direntStream.filter(Files::isRegularFile).toList();

            for (final Path dirent : dirents) {
                for (PathMatcher matcher : matchers) {
                    if (matcher.matches(dirent)) {
                        toReturn.add(dirent.toAbsolutePath().toString());
                        break;
                    }
                }
            }
        }

        return toReturn;
    }
}
