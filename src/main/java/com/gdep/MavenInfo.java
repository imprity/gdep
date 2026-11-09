package com.gdep;

import com.dslplatform.json.CompiledJson;
import java.util.*;

@CompiledJson(onUnknown = CompiledJson.Behavior.FAIL)
public class MavenInfo {
    public Set<String> externalSourceJars;

    public MavenInfo() {}

    public MavenInfo(Set<String> externalSourceJars) {
        this.externalSourceJars = externalSourceJars;
    }
}
