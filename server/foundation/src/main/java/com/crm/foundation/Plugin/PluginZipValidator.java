package com.crm.foundation.Plugin;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Validates a plugin ZIP package: every entry must be traversal-safe (no
 * absolute paths, no {@code ..} segments — Zip Slip), and the package must
 * contain a root-level {@code plugin.yaml}.
 */
@Component
public class PluginZipValidator {

    private static final String MANIFEST_ENTRY = "plugin.yaml";

    /**
     * Validates all entries and returns the {@code plugin.yaml} content.
     *
     * @throws InvalidPluginPackageException with the rejection reason
     */
    public String validateAndReadManifest(Path zipPath) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry manifest = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                requireTraversalSafe(entry.getName());
                if (MANIFEST_ENTRY.equals(entry.getName())) {
                    manifest = entry;
                }
            }
            if (manifest == null) {
                throw new InvalidPluginPackageException("package does not contain a root-level plugin.yaml");
            }
            try (InputStream in = zip.getInputStream(manifest)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new InvalidPluginPackageException(
                "not a readable ZIP archive: " + zipPath.getFileName() + " (" + e.getMessage() + ")", e);
        }
    }

    private static void requireTraversalSafe(String entryName) {
        String normalized = entryName.replace('\\', '/');
        boolean absolute = normalized.startsWith("/");
        boolean traversal = false;
        for (String segment : normalized.split("/")) {
            if ("..".equals(segment)) {
                traversal = true;
                break;
            }
        }
        if (absolute || traversal) {
            throw new InvalidPluginPackageException("unsafe ZIP entry path (Zip Slip): " + entryName);
        }
    }
}
