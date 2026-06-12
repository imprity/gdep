package com.gdep;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.events.OperationType;
import org.gradle.tooling.events.ProgressEvent;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.UnsupportedMethodException;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseJavaSourceSettings;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final DslJson<Object> dslJson =
            new DslJson<>(Settings.withRuntime().includeServiceLoader());
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    @CompiledJson(onUnknown = CompiledJson.Behavior.FAIL)
    public static class GradleToolingInfo {
        public Set<String> externalSourceJars;
        public Set<String> projectSourceDirectories;
        public Set<String> jdkPaths;

        public GradleToolingInfo() {}

        public GradleToolingInfo(
                Set<String> externalSourceJars, Set<String> projectSourceDirectories, Set<String> jdkPaths) {
            this.externalSourceJars = externalSourceJars;
            this.projectSourceDirectories = projectSourceDirectories;
            this.jdkPaths = jdkPaths;
        }
    }

    public static void main(String[] args) throws IOException {
        int exitCode = run();
        System.exit(exitCode);
    }

    private static int run() throws IOException {
        final String projectDir = System.getProperty("gdep.internal.project.dir");

        if (projectDir == null) {
            logger.error("gdep.internal.project.dir is not set.");
            return 1;
        }

        Set<String> externSourceJarPaths = new HashSet<>();

        Set<String> jdkPaths = new HashSet<>();

        Set<String> projectSourceDirs = new HashSet<>();

        logger.info("connecting to gradle...");
        try (ProjectConnection connection = GradleConnector.newConnector()
                .forProjectDirectory(new File(projectDir))
                .connect()) {

            ModelBuilder<EclipseProject> modelBuilder = connection.model(EclipseProject.class);
            modelBuilder.addProgressListener(
                    (ProgressEvent event) -> {
                        if (event.getDisplayName() != null
                                && !event.getDisplayName().equals("<null>")) {
                            logger.info("{}", event.getDisplayName());
                        }
                    },
                    OperationType.FILE_DOWNLOAD,
                    OperationType.PROJECT_CONFIGURATION,
                    OperationType.BUILD_PHASE,
                    OperationType.PROBLEMS);
            EclipseProject project = modelBuilder.get();

            // get external source jar paths
            Set<EclipseExternalDependency> deps = getProjectDependencies(project);
            for (final EclipseExternalDependency dep : deps) {
                File sourceFile = dep.getSource();

                if (sourceFile != null) {
                    externSourceJarPaths.add(Util.cleanPath(sourceFile.getCanonicalPath()));
                }
            }

            jdkPaths = getJdkPaths(project);

            // get project source directories
            // TODO: implement include and exclude patterns if you can
            Set<EclipseSourceDirectory> srcDirs = getProjectSourceDirs(project);
            for (final EclipseSourceDirectory dir : srcDirs) {
                projectSourceDirs.add(Util.cleanPath(dir.getDirectory().getAbsolutePath()));
            }
        }

        GradleToolingInfo info = new GradleToolingInfo(externSourceJarPaths, projectSourceDirs, jdkPaths);

        dslJson.serialize(info, System.out);

        return 0;
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

    private static Set<String> getJdkPaths(EclipseProject project) {
        Set<String> jdkPaths = new HashSet<>();
        getJdkPathsImpl(jdkPaths, project);

        return jdkPaths;
    }

    private static void getJdkPathsImpl(Set<String> jdkPaths, EclipseProject project) {
        try {
            EclipseJavaSourceSettings javaSettings = project.getJavaSourceSettings();
            if (javaSettings != null) {
                String jdkPath =
                        Util.cleanPath(javaSettings.getJdk().getJavaHome().getPath());
                jdkPaths.add(jdkPath);
            }
        } catch (UnsupportedMethodException e) {
            // welp, nothing we can do if it's unsupported
        }

        DomainObjectSet<? extends EclipseProject> children = project.getChildren();

        for (final EclipseProject child : children) {
            getJdkPathsImpl(jdkPaths, child);
        }
    }
}
