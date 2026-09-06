package com.vwww.mira.runtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.CRC32;

/** Verified, bounded-retry writes. The old destination survives failed attempts. */
final class VerifiedExtraction {
    private static final int MAX_ATTEMPTS = 2;

    interface Source {
        InputStream open() throws IOException;
    }

    private VerifiedExtraction() {}

    static void extract(Source source, File destination, long expectedSize,
                        long expectedCrc, boolean executable) throws IOException {
        if (expectedSize < 0 || expectedCrc < 0) {
            throw new IOException("Missing ZIP integrity metadata: " + destination);
        }
        IOException failure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                extractOnce(source, destination, expectedSize, expectedCrc, executable);
                return;
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        throw new IOException("Bootstrap extraction failed after " + MAX_ATTEMPTS
            + " attempts: " + destination, failure);
    }

    private static void extractOnce(Source source, File destination, long expectedSize,
                                    long expectedCrc, boolean executable) throws IOException {
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Cannot create bootstrap directory: " + parent);
        }
        // A fixed sibling name lets the next attempt reclaim a process-interrupted write.
        File temporary = new File(parent, ".mira-extract-" + destination.getName());
        if (temporary.exists() && !temporary.delete()) {
            throw new IOException("Cannot remove interrupted extraction: " + temporary);
        }
        try {
            MessageDigest sourceHash = sha256();
            CRC32 crc = new CRC32();
            long count = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = source.open();
                 FileOutputStream output = new FileOutputStream(temporary)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > expectedSize) throw new IOException("ZIP size exceeded: " + destination);
                    crc.update(buffer, 0, read);
                    sourceHash.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                if (count != expectedSize || crc.getValue() != expectedCrc) {
                    throw new IOException("ZIP size/CRC mismatch: " + destination);
                }
                output.getFD().sync();
            }
            // Read back the actual file, not only the bytes handed to FileOutputStream.
            verifyFile(temporary, expectedSize, sourceHash.digest());
            if (!temporary.setReadable(true, true) || !temporary.setWritable(true, true)
                || (executable && !temporary.setExecutable(true, true))) {
                throw new IOException("Cannot set bootstrap permissions: " + destination);
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("Cannot replace bootstrap file: " + destination);
            }
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                throw new IOException("Cannot clean failed extraction: " + temporary);
            }
        }
    }

    static void verifyFile(File file, long expectedSize, byte[] expectedHash) throws IOException {
        if (!file.isFile() || file.length() != expectedSize) {
            throw new IOException("Extracted size mismatch: " + file);
        }
        MessageDigest hash = sha256();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
        }
        if (!MessageDigest.isEqual(expectedHash, hash.digest())) {
            throw new IOException("Extracted SHA-256 mismatch: " + file);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
