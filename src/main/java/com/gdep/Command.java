package com.gdep;

import java.util.Set;

public interface Command {
    String getName();

    String getDescription();

    void run(Set<App.JavaCode> javaCodes, String[] args) throws Exception;
}
