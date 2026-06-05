package com.gdep;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class Util {
    public static List<String> plainSplit(String str, String toSplit) {
        List<String> parts = new ArrayList<>();

        if (str.length() <= 0 || toSplit.length() <= 0) {
            parts.add(str);
            return parts;
        }

        int begin = 0;

        while (true) {
            int index = str.indexOf(toSplit, begin);

            boolean doQuit = false;

            if (index < 0) {
                index = str.length();
                doQuit = true;
            }

            String part = str.substring(begin, index);
            parts.add(part);

            if (doQuit) {
                break;
            }

            begin = index + toSplit.length();
        }

        return parts;
    }

    public static List<String> getFilesInDirectory(String dir) throws IOException {
        try (Stream<Path> dirents = Files.walk(Path.of(dir))) {
            return dirents.filter(Files::isRegularFile)
                    .map(Util::cleanPath)
                    .map(Path::toString)
                    .distinct()
                    .toList();
        }
    }

    // TODO: make it return file entires as well
    public static void extractZip(String src, String dst) throws IOException {
        Path dstPath = cleanPath(Path.of(dst));

        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
            ZipEntry entry = zis.getNextEntry();

            // TODO: have permission?
            Files.createDirectories(Path.of(dst));

            while (entry != null) {
                String name = entry.getName();
                Path entryPath = cleanPath(Path.of(dst, name));

                if (!entryPath.startsWith(dstPath)) {
                    throw new IOException("zip slip attack detected!: " + name);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Path entryParent = entryPath.getParent();
                    if (entryParent != null) {
                        Files.createDirectories(entryParent);
                    }

                    try (var outStream = new BufferedOutputStream(Files.newOutputStream(
                            entryPath,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING))) {
                        zis.transferTo(outStream);
                    }
                }

                entry = zis.getNextEntry();
            }
        }
    }

    public static int runCommand(String... commands) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(commands);
        pb.inheritIO();
        Process process = pb.start();
        process.waitFor();

        return process.exitValue();
    }

    public static byte[] hashFile(String src) throws IOException {
        MessageDigest digest = getSha256Digest();

        try (var dis = new DigestInputStream(new BufferedInputStream(new FileInputStream(src)), digest)) {
            dis.transferTo(OutputStream.nullOutputStream());

            return digest.digest();
        }
    }

    public static String hashFileToString(String src) throws IOException {
        return HexFormat.of().formatHex(hashFile(src));
    }

    public static MessageDigest getSha256Digest() {
        MessageDigest digest = null;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        return digest;
    }

    /**
     * get normalized absolute path
     */
    public static Path cleanPath(Path path) {
        return path.toAbsolutePath().normalize();
    }

    public static String cleanPath(String path) {
        return cleanPath(Path.of(path)).toString();
    }
}
