package com.gdep;

import com.dslplatform.json.DslJson;
import com.dslplatform.json.runtime.Settings;
import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App {
    private static final DslJson<Object> dslJson =
            new DslJson<>(Settings.withRuntime().includeServiceLoader());
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws IOException {
        final String projectDir = System.getProperty("gdep.internal.project.dir");

        if (projectDir == null) {
            logger.error("gdep.internal.project.dir is not set.");
            System.exit(1);
        }

        GradleToolingInfo info = GradleHandler.getGradleToolingInfo(projectDir);

        dslJson.serialize(info, System.out);
    }
}
