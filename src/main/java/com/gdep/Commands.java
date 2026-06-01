package com.gdep;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
        public void run(List<SourceCode> sourceCodes, String[] args) {
            List<String> sourceDirs =
                    sourceCodes.stream().map(x -> x.sourceDirPath()).sorted().toList();

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
        public void run(List<SourceCode> sourceCodes, String[] args) throws IOException {
            List<String> files = new ArrayList<>();

            for (final SourceCode code : sourceCodes) {
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
        public void run(List<SourceCode> sourceCodes, String[] args) throws IOException {
            if (args.length <= 0) {
                throw new GracefulException("pack command needs atleast one argument").withHelp();
            }

            record PathAndScore(String path, int score) {}

            List<PathAndScore> pathAndScores = new ArrayList<>();

            for (final SourceCode sc : sourceCodes) {
                for (final String file : sc.sourceFiles()) {
                    Path filePath = Path.of(file);
                    filePath = Path.of(sc.sourceDirPath()).relativize(filePath);
                    if (filePath.getNameCount() >= 2) {
                        filePath = filePath.subpath(1, filePath.getNameCount());
                    }
                    String newFileName = filePath.toString();

                    int score = PackScore.scoreClassNameSimilarity(newFileName, args[0]);

                    pathAndScores.add(new PathAndScore(file, score));
                }
            }

            List<PathAndScore> candidates = pathAndScores.stream()
                    .sorted(Comparator.comparingInt(PathAndScore::score)
                            .reversed()
                            .thenComparing(PathAndScore::path))
                    .limit(5)
                    .toList();

            for (final PathAndScore candidate : candidates) {
                System.out.println(candidate.path());
            }
        }
    }
}
