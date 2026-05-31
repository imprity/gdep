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
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;

public class ExternalCodeManager {
    private final String cwd;
    private final String cacheDir;

    private final Gson gson;

    private static record ExternalSourceCode(
            String sourceDirPath, List<String> sourceFiles, String sourceJarPath, String sourceJarHash)
            implements SourceCode {}

    public ExternalCodeManager(String cwd, String cacheDir) {
        this.cwd = cwd;
        this.cacheDir = cacheDir;

        this.gson = new Gson();
    }

    public List<SourceCode> getExternalSourceCodes() throws IOException {
        // collect source jar paths from gradle
        Set<String> sourceJarPaths = new HashSet<>();

        try (ProjectConnection connection = GradleConnector.newConnector()
                .forProjectDirectory(new File(cwd))
                .connect()) {

            EclipseProject project = connection.getModel(EclipseProject.class);
            Set<EclipseExternalDependency> deps = getProjectDependencies(project);

            for (final EclipseExternalDependency dep : deps) {
                File classFile = dep.getFile();
                File sourceFile = dep.getSource();
                File javaDocFile = dep.getJavadoc();

                // System.out.println(classFile);
                // System.out.println(sourceFile);
                // System.out.println(javaDocFile);

                if (sourceFile != null) {
                    sourceJarPaths.add(sourceFile.getCanonicalPath());
                }
            }
        }

        List<SourceCode> sourceCodes = new ArrayList<SourceCode>();

        for (final String jarPath : sourceJarPaths) {
            sourceCodes.add(getSourceCode(jarPath));
        }

        return sourceCodes;
    }

    private SourceCode getSourceCode(String sourceJarPath) throws IOException {
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
            String sourceDirPath = getSourceDirPath(sourceJarPath, sourceJarHash);

            Util.extractZip(sourceJarPath, sourceDirPath);

            List<String> sourceFiles = Util.getFilesInDirectory(sourceDirPath);

            sourceCode = new ExternalSourceCode(sourceDirPath, sourceFiles, sourceJarPath, sourceJarHash);

            String jsonString = gson.toJson(sourceCode);

            Files.writeString(jsonCachePath, jsonString, StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW);
        }

        return sourceCode;
    }

    private String getSourceDirPath(String sourceJarPath, String sourceJarHash) {
        if (sourceJarPath.endsWith("-sources.jar")) {
            sourceJarPath = sourceJarPath.substring(0, sourceJarPath.length() - "-sources.jar".length());
        } else if (sourceJarPath.endsWith(".jar")) {
            sourceJarPath = sourceJarPath.substring(0, sourceJarPath.length() - ".jar".length());
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
}
