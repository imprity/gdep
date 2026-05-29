package com.gdep;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class App {
    public static class FileAndHash {
        public String file;
        public String hash;
    }

    public static void main(String[] args) {
        final String cwd = System.getProperty("user.dir");
        final String cacheDir = Path.of(cwd, "cache").toString();

        List<FileAndHash> fhList = new ArrayList<>();
    
        ProjectConnection connection = GradleConnector.newConnector()
            .forProjectDirectory(new File(cwd))
            .connect();
            
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
                    FileAndHash fh = new FileAndHash();
                    fh.file = sourceFile.getCanonicalPath();
                    fhList.add(fh);
                }
            }
        } catch(Exception e) {
            e.printStackTrace();
        }finally {
            connection.close();
        }

        try {
            for (final FileAndHash fh : fhList) {
                fh.hash = Util.hashFileToString(fh.file);
            }

            Set<String> hashSet = getCacheHashSet(cacheDir);

            for (final FileAndHash fh : fhList) {
                if (!hashSet.contains(fh.hash)) {
                    String fileName = Path.of(fh.file).getFileName().toString();
                    Util.extractZip(
                            fh.file,
                            Path.of(cacheDir, fileName + "-" + fh.hash).toString()
                    );
                }
            }
        }catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static Set<String> getCacheHashSet(String cacheDir) throws IOException {
        Path cacheDirPath = Path.of(cacheDir);

        Set<String> hashes = new HashSet<>();

        if (!Files.isDirectory(cacheDirPath)) {
            return hashes;
        }

        try(DirectoryStream<Path> dirStream = Files.newDirectoryStream(cacheDirPath)) {
            for (Path dirent : dirStream) {
                if (Files.isDirectory(dirent)) {
                    String name = dirent.getFileName().toString();
                    if (name.length() >= 64) {
                        hashes.add(name.substring(name.length() - 64, name.length()));
                    }
                }
            }
        }

        return hashes;
    }

    private static Set<EclipseExternalDependency> getProjectDependencies(
            EclipseProject project
    ) {
        Set<EclipseExternalDependency> deps = new HashSet<>();
        getProjectDependenciesImpl(deps, project);

        return deps;
    }

    private static void getProjectDependenciesImpl(
            Set<EclipseExternalDependency> deps,
            EclipseProject project
    ) {
        DomainObjectSet<? extends EclipseExternalDependency> projectDeps = project.getClasspath();

        deps.addAll(projectDeps);

        DomainObjectSet<? extends EclipseProject> children = project.getChildren();

        for (final EclipseProject child : children) {
            getProjectDependenciesImpl(deps, child);
        }
    }
}
