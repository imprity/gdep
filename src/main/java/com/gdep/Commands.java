package com.gdep;

import com.gdep.App.JavaCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Commands {
    public static class Dirs implements Command {
        @Override
        public String getName() {
            return "dirs";
        }

        @Override
        public String getDescription() {
            return "list source directories";
        }

        @Override
        public void run(Set<App.JavaCode> javaCodes, String[] args) {
            List<String> sourceDirs =
                    javaCodes.stream().map(x -> x.sourceDirPath()).sorted().toList();

            for (final String dir : sourceDirs) {
                System.out.println(dir);
            }
        }
    }

    public static class Files implements Command {
        @Override
        public String getName() {
            return "files";
        }

        @Override
        public String getDescription() {
            return "list source files";
        }

        @Override
        public void run(Set<App.JavaCode> javaCodes, String[] args) throws IOException {
            List<String> files = new ArrayList<>();

            for (final App.JavaCode code : javaCodes) {
                List<String> subFiles = Util.getFilesInDirectory(code.sourceDirPath());
                files.addAll(subFiles);
            }

            files.sort(Comparator.naturalOrder());

            for (String file : files) {
                System.out.println(file);
            }
        }
    }

    public static class Pack implements Command {
        @Override
        public String getName() {
            return "pack";
        }

        @Override
        public String getDescription() {
            return "search file using class path. e.g. gdep pack o.s.w.s.DispatcherServlet";
        }

        @Override
        public void run(Set<App.JavaCode> javaCodes, String[] args) throws IOException {
            if (args.length <= 0) {
                throw new GracefulException("pack command needs atleast one argument").withHelp();
            }

            record PathAndScore(String path, int score) {}

            Set<PathAndScore> pathAndScores = new HashSet<>();

            for (final JavaCode jc : javaCodes) {
                List<String> files = Util.getFilesInDirectory(jc.sourceDirPath());

                for (final String file : files) {
                    int score = PackScore.scoreClassNameSimilarity(file, args[0]);

                    pathAndScores.add(new PathAndScore(file, score));
                }
            }

            List<String> candidates = pathAndScores.stream()
                    .sorted((a, b) -> b.score() - a.score())
                    .limit(5)
                    .map(x -> x.path())
                    .toList();

            for (final String candidate : candidates) {
                System.out.println(candidate);
            }
        }
    }
}
