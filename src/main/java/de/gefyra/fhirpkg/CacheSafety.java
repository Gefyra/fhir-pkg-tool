package de.gefyra.fhirpkg;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class CacheSafety {

  private static final String EXPECTED_CACHE_METADATA_VERSION = "4";

  private CacheSafety() {
  }

  static String expectedCacheMetadataVersion() {
    return EXPECTED_CACHE_METADATA_VERSION;
  }

  static Optional<String> parseCacheVersionFromIni(String iniText) {
    if (iniText == null || iniText.isBlank()) {
      return Optional.empty();
    }
    String section = "";
    String[] lines = iniText.split("\\R");
    for (String rawLine : lines) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
        continue;
      }
      if (line.startsWith("[") && line.endsWith("]")) {
        section = line.substring(1, line.length() - 1).trim();
        continue;
      }
      int eqIdx = line.indexOf('=');
      if (eqIdx < 0) {
        continue;
      }
      String key = line.substring(0, eqIdx).trim();
      String value = line.substring(eqIdx + 1).trim();
      if ("cache".equalsIgnoreCase(section) && "version".equalsIgnoreCase(key)
          && !value.isBlank()) {
        return Optional.of(value);
      }
    }
    return Optional.empty();
  }

  static boolean isSupportedCacheVersion(String version) {
    return EXPECTED_CACHE_METADATA_VERSION.equals(Optional.ofNullable(version).orElse("").trim());
  }

  static boolean shouldValidateDefaultCacheSafety(Path effectiveCacheDir, Path defaultCacheDir) {
    if (effectiveCacheDir == null || defaultCacheDir == null) {
      return false;
    }
    return defaultCacheDir.toAbsolutePath().normalize()
        .equals(effectiveCacheDir.toAbsolutePath().normalize());
  }

  static Optional<String> readCacheVersionFromCacheIni(Path cacheDir) throws IOException {
    Path iniPath = cacheDir.resolve("packages.ini");
    if (!Files.exists(iniPath)) {
      return Optional.empty();
    }
    return parseCacheVersionFromIni(Files.readString(iniPath));
  }

  static List<Path> findLockFiles(Path cacheDir) throws IOException {
    List<Path> lockFiles = new ArrayList<>();
    if (!Files.exists(cacheDir) || !Files.isDirectory(cacheDir)) {
      return lockFiles;
    }
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.lock")) {
      for (Path lock : stream) {
        lockFiles.add(lock);
      }
    }
    return lockFiles;
  }

  static int deleteLockFiles(List<Path> lockFiles) throws IOException {
    int deleted = 0;
    for (Path lock : lockFiles) {
      try {
        if (Files.deleteIfExists(lock)) {
          deleted++;
        }
      } catch (IOException e) {
        throw new IOException("Failed to delete lock file " + lock + " (" + e.getMessage() + ")",
            e);
      }
    }
    return deleted;
  }
}

