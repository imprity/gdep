package com.gdep;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    // ============================
    // SourceCode implementations
    // ============================

    private static record ExternalSourceCode(
            String sourceDirPath, List<String> sourceFiles, String sourceJarPath, String sourceJarHash)
            implements SourceCode {}

    private static record ProjectSourceCode(String sourceDirPath, List<String> sourceFiles) implements SourceCode {}

    // ============================

    public CodeManager(String cwd, String cacheDir) {
        this.cwd = cwd;
        this.cacheDir = cacheDir;

        this.gson = new Gson();
    }

    public List<SourceCode> getExternalSourceCodes() throws IOException {
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

        List<SourceCode> sourceCodes = new ArrayList<SourceCode>();

        // get SourceCode from externSourceJarPaths
        for (final String jarPath : externSourceJarPaths) {
            sourceCodes.add(getSourceCodeFromJar(jarPath));
        }

        // get SourceCode from jdk (if it exists)
        if (jdkPath != null) {
            Path jdkZipPath = Path.of(jdkPath, "lib", "src.zip");
            try {
                sourceCodes.add(getSourceCodeFromJar(jdkZipPath.toString()));
            } catch (Exception e) {
                logger.error("failed to get jdk sources from {}", jdkZipPath, e);
            }
        }

        // get SourceCode from projectSourceDirs
        for (final String srcDir : projectSourceDirs) {
            List<String> files = Util.getFilesInDirectory(srcDir);

            sourceCodes.add(new ProjectSourceCode(srcDir, files));
        }

        return sourceCodes;
    }

    private SourceCode getSourceCodeFromJar(String sourceJarPath) throws IOException {
        // first get hash of the jar
        String sourceJarHash = Util.hashFileToString(sourceJarPath);

        // check cache
        Path jsonCachePath = Path.of(getJsonCachePath(sourceJarHash));

        ExternalSourceCode sourceCode;

        if (Files.exists(jsonCachePath) && Files.isRegularFile(jsonCachePath)) {
            // if cache exists, we just parse json cache
            String jsonString = Files.readString(jsonCachePath);
            sourceCode = gson.fromJson(jsonString, ExternalSourceCode.class);
        } else {
            // if it doesn't exist in cache, create a cache
            String sourceDirPath = getSourceCacheDirPath(sourceJarPath, sourceJarHash);

            Util.extractZip(sourceJarPath, sourceDirPath);

            List<String> sourceFiles = Util.getFilesInDirectory(sourceDirPath);

            sourceCode = new ExternalSourceCode(sourceDirPath, sourceFiles, sourceJarPath, sourceJarHash);

            String jsonString = gson.toJson(sourceCode);

            Files.writeString(jsonCachePath, jsonString, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        }

        return sourceCode;
    }

    private String getSourceCacheDirPath(String sourceJarPath, String sourceJarHash) {
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

    private String getJsonCachePath(String sourceJarHash) {
        String fileName = sourceJarHash + ".data.json";

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
}
