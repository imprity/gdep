package com.gdep;

import java.nio.file.FileSystems;
import java.nio.file.Path;

public class Util {
    /**
     * get normalized absolute path
     */
    public static Path cleanPath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    public static String cleanPath(String path) {
        return cleanPath(FileSystems.getDefault().getPath(path)).toString();
    }
}
