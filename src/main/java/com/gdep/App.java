package com.gdep;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class App {
    public static void main(String[] args) {
        final String cwd = System.getProperty("user.dir");
        final String cacheDir = Path.of(cwd, "cache").toString();

        class FileAndHash {
            public String file;
            public String hash;
        };

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

            for (final FileAndHash fh : fhList) {
                String fileName = Path.of(fh.file).getFileName().toString();
                Util.extractZip(
                        fh.file,
                        Path.of(cacheDir, fileName + "-" + fh.hash).toString()
                );
            }
        }catch (Exception e) {
            e.printStackTrace();
        }
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
