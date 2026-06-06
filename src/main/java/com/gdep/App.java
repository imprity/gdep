package com.gdep;

import com.dslplatform.json.CompiledJson;
import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import java.io.File;
import java.io.IOException;
import java.util.*;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.UnsupportedMethodException;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final DslJson<Object> dslJson =
            new DslJson<>(Settings.withRuntime().includeServiceLoader());
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    @CompiledJson(onUnknown = CompiledJson.Behavior.FAIL)
    public static record GradleToolingInfo(
            Set<String> externalSourceJars,
            Set<String> projectSourceDirectories,
            @Nullable String jdkPath) {

        public GradleToolingInfo {
            externalSourceJars = Collections.unmodifiableSet(externalSourceJars);
            projectSourceDirectories = Collections.unmodifiableSet(projectSourceDirectories);
        }
    }

    public static void main(String[] args) throws IOException {
        int exitCode = run();
        System.exit(exitCode);
    }

    private static int run() throws IOException {
        final String cwd = System.getProperty("user.dir");

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
                    externSourceJarPaths.add(Util.cleanPath(sourceFile.getCanonicalPath()));
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
                    jdkPath = Util.cleanPath(javaSettings.getJdk().getJavaHome().getPath());
                }
            } catch (UnsupportedMethodException e) {
                logger.error("failed to get jdk path", e);
            }

            // get project source directories
            // TODO: implement include and exclude patterns if you can
            Set<EclipseSourceDirectory> srcDirs = getProjectSourceDirs(project);
            for (final EclipseSourceDirectory dir : srcDirs) {
                projectSourceDirs.add(Util.cleanPath(dir.getPath()));
            }
        }

        GradleToolingInfo info = new GradleToolingInfo(externSourceJarPaths, projectSourceDirs, jdkPath);

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
}
