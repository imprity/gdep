package com.gdep;

import java.util.List;

public class PackScore {
    public static int scoreClassNameSimilarity(String filePath, String className) {
        if (filePath.length() <= 0 || className.length() <= 0) {
            return Integer.MIN_VALUE;
        }

        filePath = filePath.toLowerCase();
        className = className.toLowerCase();

        // normalize slash
        filePath = filePath.replace("\\", "/");

        // remove file extension from filePath
        {
            int slashIndex = filePath.lastIndexOf('/');
            if (slashIndex < 0) {
                slashIndex = 0;
            }

            int dotIndex = filePath.indexOf('.', slashIndex + 1);
            if (dotIndex >= 0) {
                filePath = filePath.substring(0, dotIndex);
            }
        }

        List<String> filePathParts = Util.plainSplit(filePath, "/");
        List<String> classNameParts = Util.plainSplit(className, ".");

        int limit = Math.min(filePathParts.size(), classNameParts.size());

        int score = 0;

        for (int i = 0; i < limit; i++) {
            String filePathPart = filePathParts.get(filePathParts.size() - 1 - i);
            String classNamePart = classNameParts.get(classNameParts.size() - 1 - i);

            if (i == 0) {
                score -= FuzzyMatch.fuzzyMatch(filePathPart, classNamePart);
            } else {
                if (filePathPart.equals(classNamePart)) {
                    score += 1;
                }

                if (classNamePart.length() == 1 && filePathPart.startsWith(classNamePart)) {
                    score += 1;
                }
            }
        }

        return score;
    }
}
