package com.gdep;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import java.io.IOException;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.VersionResolutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final DslJson<Object> dslJson =
            new DslJson<>(Settings.withRuntime().includeServiceLoader());
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args)
            throws IOException, VersionResolutionException, DependencyCollectionException, ArtifactResolutionException,
                    ArtifactDescriptorException {
        final String projectDir = System.getProperty("gdep.internal.project.dir");
        final String projectType = System.getProperty("gdep.internal.project.type");

        if (projectDir == null) {
            logger.error("gdep.internal.project.dir is not set.");
            System.exit(1);
        }

        if (projectType == null) {
            logger.error("gdep.internal.project.type is not set.");
            System.exit(1);
        }

        if (projectType.equals("gradle")) {
            GradleToolingInfo info = GradleHandler.getGradleToolingInfo(projectDir);
            dslJson.serialize(info, System.out);
        } else if (projectType.equals("maven")) {
            MavenInfo info = MavenHandler.getMavenInfo(projectDir);
            dslJson.serialize(info, System.out);
        } else {
            logger.error("gdep.internal.project.type {} is unsupported.", projectType);
            System.exit(1);
        }
    }
}
