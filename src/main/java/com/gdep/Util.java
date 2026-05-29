package com.gdep;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.nio.file.Files;

public class Util {
    public static void extractZip(
        String src,
        String dst
    ) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(src)))) {
            ZipEntry entry = zis.getNextEntry();
            
            // TODO: have permission?
            Files.createDirectories(Path.of(dst));
            
            while (entry != null) {
                String name = entry.getName();
                Path entryPath = Path.of(dst, name);
                
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());

                    try (var outStream = new BufferedOutputStream(Files.newOutputStream(
                        entryPath,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    ))) {
                        zis.transferTo(outStream);
                    }
                }
                
                entry = zis.getNextEntry();
            }
        }
    }

    public static byte[] hashFile(String src) throws IOException {
        MessageDigest digest = null;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        try (var in = new BufferedInputStream(new FileInputStream(src))) {
            DigestInputStream dis = new DigestInputStream(in, digest);
            dis.transferTo(OutputStream.nullOutputStream());

            return digest.digest();
        }
    }

    public static String hashFileToString(String src) throws IOException {
        return HexFormat.of().formatHex(hashFile(src));
    }
}
