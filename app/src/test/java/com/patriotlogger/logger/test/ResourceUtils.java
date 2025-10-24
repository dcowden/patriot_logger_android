package com.patriotlogger.logger.test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Utilities for listing and opening resources on the classpath.
 * Handles both exploded (file:) and packaged (jar:) classpath entries.
 */
public final class ResourceUtils {

    private ResourceUtils() {}

    /**
     * Lists files directly under a resource directory (e.g., "data_samples") using the classloader.
     * Returns classpath-style resource paths like "data_samples/foo.csv".
     */
    public static List<String> listResourceFiles(String resourceDir) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ResourceUtils.class.getClassLoader();

            String base = resourceDir.startsWith("/") ? resourceDir.substring(1) : resourceDir;
            Set<String> results = new LinkedHashSet<>();

            URL url = cl.getResource(base);
            if (url != null) {
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    results.addAll(listFilesFromFileUrl(url));
                } else if ("jar".equals(protocol)) {
                    results.addAll(listFilesFromJarUrl(url, base));
                } else {
                    results.addAll(scanAllClasspathRoots(base, cl));
                }
            } else {
                // Not directly resolvable; scan classpath roots
                results.addAll(scanAllClasspathRoots(base, cl));
            }

            // Keep only regular files
            List<String> out = new ArrayList<>();
            for (String p : results) {
                if (!p.endsWith("/")) out.add(p);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> listFilesFromFileUrl(URL dirUrl) throws IOException {
        try {
            URI uri = dirUrl.toURI();
            Path dirPath = Paths.get(uri);
            List<String> items = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dirPath)) {
                for (Path p : ds) {
                    if (Files.isRegularFile(p)) {
                        String lastSegment = dirPath.getFileName().toString();
                        items.add(lastSegment + "/" + p.getFileName().toString());
                    }
                }
            }
            return items;
        } catch (Exception e) {
            throw new IOException("Failed listing files from file URL: " + dirUrl, e);
        }
    }

    static List<String> listFilesFromJarUrl(URL jarDirUrl, String base) throws IOException {
        List<String> items = new ArrayList<>();
        try {
            JarURLConnection conn = (JarURLConnection) jarDirUrl.openConnection();
            try (JarFile jar = conn.getJarFile()) {
                String prefix = base.endsWith("/") ? base : base + "/";
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    String name = e.getName();
                    if (name.startsWith(prefix) && !name.equals(prefix) && !e.isDirectory()) {
                        items.add(name);
                    }
                }
            }
        } catch (ClassCastException cce) {
            // Fallback: parse the URL manually if not a JarURLConnection
            items.addAll(scanJarByManualParsing(jarDirUrl));
        }
        return items;
    }

    static List<String> scanAllClasspathRoots(String base, ClassLoader cl) throws IOException {
        List<String> items = new ArrayList<>();
        String prefix = base.endsWith("/") ? base : base + "/";
        Enumeration<URL> roots = cl.getResources("");
        while (roots.hasMoreElements()) {
            URL root = roots.nextElement();
            String protocol = root.getProtocol();
            if ("jar".equals(protocol)) {
                items.addAll(listFilesFromJarUrl(root, base));
            } else if ("file".equals(protocol)) {
                try {
                    Path rootPath = Paths.get(root.toURI());
                    Path target = rootPath.resolve(base);
                    if (Files.isDirectory(target)) {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(target)) {
                            for (Path p : ds) {
                                if (Files.isRegularFile(p)) {
                                    items.add(prefix + p.getFileName().toString());
                                }
                            }
                        }
                    }
                } catch (Exception ignore) {}
            }
        }
        return items;
    }

    static List<String> scanJarByManualParsing(URL jarDirUrl) throws IOException {
        List<String> items = new ArrayList<>();
        String spec = jarDirUrl.toString(); // jar:file:/...!/data_samples
        int bang = spec.indexOf("!/");
        if (bang < 0) return items;

        String jarPathEncoded = spec.substring("jar:".length(), bang); // file:/...
        String insidePath = spec.substring(bang + 2); // data_samples or ""
        String jarFilePath = jarPathEncoded.startsWith("file:")
                ? URLDecoder.decode(jarPathEncoded.substring("file:".length()), StandardCharsets.UTF_8)
                : URLDecoder.decode(jarPathEncoded, StandardCharsets.UTF_8);
        String prefix = insidePath.endsWith("/") ? insidePath : insidePath + "/";

        try (JarFile jar = new JarFile(jarFilePath)) {
            Enumeration<JarEntry> en = jar.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                String name = e.getName();
                if (name.startsWith(prefix) && !name.equals(prefix) && !e.isDirectory()) {
                    items.add(name);
                }
            }
        }
        return items;
    }

    /**
     * Opens a resource as a UTF-8 BufferedReader.
     */
    public static BufferedReader openResourceAsReader(String resourcePath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = ResourceUtils.class.getClassLoader();
        InputStream is = cl.getResourceAsStream(resourcePath);
        if (is == null) throw new IOException("Resource not found: " + resourcePath);
        return new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
    }
}

