package com.gdep;

import com.dslplatform.json.CompiledJson;
import java.util.*;

@CompiledJson(onUnknown = CompiledJson.Behavior.FAIL)
public class GradleToolingInfo {
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
