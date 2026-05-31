package com.gdep;

import java.util.List;

public interface Command {
    String getName();

    String getDescription();

    void run(List<SourceCode> sourceCodes, String[] args) throws Exception;
}
