package com.gdep;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Util {
    private static Pattern whiteSpacePattern = Pattern.compile("^\\p{javaWhitespace}*$");

    /**
     * get normalized absolute path
     */
    public static Path cleanPath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    public static String cleanPath(String path) {
        return cleanPath(FileSystems.getDefault().getPath(path)).toString();
    }

    // added since java 8 doesn't have isBlank
    // also true if str is null
    public static boolean isStringBlank(String str) {
        if (str == null) {
            return true;
        }
        if (str.isEmpty()) {
            return true;
        }

        Matcher matcher = whiteSpacePattern.matcher(str);
        if (matcher.matches()) {
            return true;
        }

        return false;
    }
}
