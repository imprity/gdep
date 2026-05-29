package com.gdep;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;

public class App {
    public static class JavaCode {
        public String sourceJarPath;
        public String sourceJarHash;

        public String sourceDirPath;
    }

    public static record CachedJavaCode(String sourceJarHash, String sourceDirPath) {}

    public static void main(String[] args) {
        final String cwd = System.getProperty("user.dir");
        final String cacheDir = Path.of(cwd, "cache").toString();

        List<JavaCode> jcList = new ArrayList<>();

        ProjectConnection connection = GradleConnector.newConnector()
                .forProjectDirectory(new File(cwd))
                .connect();

        // collect where source jar files are for external dependencies
        try {
            EclipseProject project = connection.getModel(EclipseProject.class);
            Set<EclipseExternalDependency> deps = getProjectDependencies(project);

            for (final EclipseExternalDependency dep : deps) {
                File classFile = dep.getFile();
                File sourceFile = dep.getSource();
                File javaDocFile = dep.getJavadoc();

                System.out.println(classFile);
                System.out.println(sourceFile);
                System.out.println(javaDocFile);

                if (sourceFile != null) {
                    JavaCode jc = new JavaCode();
                    jc.sourceJarPath = sourceFile.getCanonicalPath();
                    jcList.add(jc);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            connection.close();
        }

        try {
            for (final JavaCode jc : jcList) {
                jc.sourceJarHash = Util.hashFileToString(jc.sourceJarPath);
            }

            Map<String, CachedJavaCode> cachedJcs = getCachedJavaCode(cacheDir);

            for (final JavaCode jc : jcList) {
                if (!cachedJcs.containsKey(jc.sourceJarHash)) {
                    String fileName = Path.of(jc.sourceJarPath).getFileName().toString();

                    jc.sourceDirPath =
                            Path.of(cacheDir, fileName + "-" + jc.sourceJarHash).toString();

                    Util.extractZip(jc.sourceJarPath, jc.sourceDirPath);
                } else {
                    jc.sourceDirPath = cachedJcs.get(jc.sourceJarHash).sourceDirPath();
                }
            }

            for (final JavaCode jc : jcList) {
                runCommand("rg", "-i", "meme", jc.sourceDirPath);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int runCommand(String... commands) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.inheritIO();
        Process process = pb.start();
        process.waitFor();

        return process.exitValue();
    }

    private static Map<String, CachedJavaCode> getCachedJavaCode(String cacheDir) throws IOException {

        Path cacheDirPath = Path.of(cacheDir);

        Map<String, CachedJavaCode> caches = new HashMap<>();

        if (!Files.isDirectory(cacheDirPath)) {
            return caches;
        }

        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(cacheDirPath)) {
            for (Path dirent : dirStream) {
                if (Files.isDirectory(dirent)) {
                    String name = dirent.getFileName().toString();
                    if (name.length() >= 64) {
                        String hash = name.substring(name.length() - 64, name.length());
                        caches.put(
                                hash,
                                new CachedJavaCode(hash, dirent.toAbsolutePath().toString()));
                    }
                }
            }
        }

        return caches;
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
