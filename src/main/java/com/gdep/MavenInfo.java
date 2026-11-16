package com.gdep;

import com.dslplatform.json.CompiledJson;
import java.util.*;

@CompiledJson(onUnknown = CompiledJson.Behavior.FAIL)
public class MavenInfo {
    public Set<String> externalSourceJars;
    public Set<String> projectSourceDirectories;
    public Set<String> jdkPaths;

    public MavenInfo() {}

    public MavenInfo(Set<String> externalSourceJars, Set<String> projectSourceDirectories, Set<String> jdkPaths) {
        this.externalSourceJars = externalSourceJars;
        this.projectSourceDirectories = projectSourceDirectories;
        this.jdkPaths = jdkPaths;
    }
}
