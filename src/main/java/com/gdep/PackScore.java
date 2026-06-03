package com.gdep;

import com.gdep.FuzzyMatch.FuzzyMatchResult;
import java.util.List;

public class PackScore {
    public static int scoreClassNameSimilarity(String filePath, String className) {
        if (filePath.length() <= 0 || className.length() <= 0) {
            return Integer.MIN_VALUE;
        }

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

        int score = 0;

        String filePathAbbreviated = getAbbreviated(filePathParts);
        String classNameAbbreviated = getAbbreviated(classNameParts);

        score -=
                FuzzyMatch.fuzzyMatch(filePathAbbreviated, classNameAbbreviated).distance() * 10;

        String filePath2 = filePath.replace("/", ".");
        String className2 = removeLogNoise(classNameParts);

        {
            String str = filePath2.length() > className2.length() ? filePath2 : className2;
            String sub = filePath2.length() > className2.length() ? className2 : filePath2;

            FuzzyMatchResult res = FuzzyMatch.fuzzyMatch(str, sub);

            score -= res.distance() * 10;
            score -= Math.abs(str.length() - res.length());
        }

        return score;
    }

    private static String getAbbreviated(List<String> parts) {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);

            if (!part.isEmpty()) {
                sb.append(Character.toLowerCase(part.charAt(0)));
            }

            if (i + 1 < parts.size()) {
                sb.append(".");
            }
        }

        return sb.toString();
    }

    private static String removeLogNoise(List<String> classNameParts) {
        StringBuilder sb = new StringBuilder();

        boolean abbrevEnded = false;

        for (int i = 0; i < classNameParts.size(); i++) {
            String part = classNameParts.get(i);

            if (part.length() > 1) {
                abbrevEnded = true;
            }

            if (!abbrevEnded) {
                continue;
            }

            if (i == classNameParts.size() - 1) { // last part
                int dollarIndex = part.indexOf("$");

                if (dollarIndex >= 0) {
                    part = part.substring(0, dollarIndex);
                }
                sb.append(part);
            } else {
                sb.append(part);
                sb.append(".");
            }
        }

        return sb.toString();
    }
}
