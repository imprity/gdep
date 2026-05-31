package com.gdep;

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
        public void run(List<SourceCode> javaCodes, String[] args) {
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
        public void run(List<SourceCode> javaCodes, String[] args) throws IOException {
            List<String> files = new ArrayList<>();

            for (final SourceCode code : javaCodes) {
                files.addAll(code.sourceFiles());
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
        public void run(List<SourceCode> javaCodes, String[] args) throws IOException {
            if (args.length <= 0) {
                throw new GracefulException("pack command needs atleast one argument").withHelp();
            }

            record PathAndScore(String path, int score) {
                @Override
                public int hashCode() {
                    return path.hashCode();
                }

                @Override
                public boolean equals(Object o) {
                    if (this == o) return true;
                    if (o == null) return false;
                    if (o instanceof PathAndScore other) {
                        return path.equals(other.path()) && score == other.score();
                    }
                    return false;
                }
            }

            Set<PathAndScore> pathAndScores = new HashSet<>();

            for (final SourceCode jc : javaCodes) {
                for (final String file : jc.sourceFiles()) {
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
