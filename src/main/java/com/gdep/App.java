package com.gdep;

import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;

import java.io.File;

public class App {
    public static void main(String[] args) {
        ProjectConnection connection = GradleConnector.newConnector()
            .forProjectDirectory(new File(System.getProperty("user.dir")))
            .connect();
            
        try {
            EclipseProject project = connection.getModel(EclipseProject.class);
            DomainObjectSet<? extends EclipseExternalDependency> deps = project.getClasspath();

            for (final EclipseExternalDependency dep : deps) {
                File classFile = dep.getFile();
                File sourceFile = dep.getSource();
                File javaDocFile = dep.getJavadoc();

                System.out.println(classFile);
                System.out.println(sourceFile);
                System.out.println(javaDocFile);
            }
        } finally {
            connection.close();
        }
    }
}
