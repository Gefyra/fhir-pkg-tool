package de.gefyra.fhirpkg.cache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.utilities.IniFile;

public final class CacheSafety {

  private static final String EXPECTED_CACHE_METADATA_VERSION = "4";

  private CacheSafety() {
  }

  public static String expectedCacheMetadataVersion() {
    return EXPECTED_CACHE_METADATA_VERSION;
  }

  public static Optional<String> parseCacheVersionFromIni(String iniText) {
    if (iniText == null || iniText.isBlank()) {
      return Optional.empty();
    }
    try {
      IniFile iniFile = new IniFile(
          new ByteArrayInputStream(iniText.getBytes(StandardCharsets.UTF_8)));
      String value = iniFile.getStringProperty("cache", "version");
      if (value == null || value.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(value.trim());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  public static boolean isSupportedCacheVersion(String version) {
    return EXPECTED_CACHE_METADATA_VERSION.equals(Optional.ofNullable(version).orElse("").trim());
  }

  public static boolean shouldValidateDefaultCacheSafety(Path effectiveCacheDir,
      Path defaultCacheDir) {
    if (effectiveCacheDir == null || defaultCacheDir == null) {
      return false;
    }
    return defaultCacheDir.toAbsolutePath().normalize()
        .equals(effectiveCacheDir.toAbsolutePath().normalize());
  }

  public static Optional<String> readCacheVersionFromCacheIni(Path cacheDir) throws IOException {
    Path iniPath = cacheDir.resolve("packages.ini");
    if (!Files.exists(iniPath)) {
      return Optional.empty();
    }
    return parseCacheVersionFromIni(Files.readString(iniPath));
  }

  public static List<Path> findLockFiles(Path cacheDir) throws IOException {
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

  public static int deleteLockFiles(List<Path> lockFiles) throws IOException {
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
