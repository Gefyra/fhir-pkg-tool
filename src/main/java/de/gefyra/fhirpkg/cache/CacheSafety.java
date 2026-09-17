package de.gefyra.fhirpkg.cache;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.hl7.fhir.utilities.IniFile;

public final class CacheSafety {

  private static final String EXPECTED_CACHE_METADATA_VERSION = "4";

  /**
   * How long a lock file must have been untouched before it is even considered for deletion.
   * FilesystemPackageCacheManagerLocks creates the file first and takes the OS lock a moment
   * later, so a brand-new file that is not locked yet may still belong to a process that is about
   * to use it. Anything older than this has long left that window.
   */
  public static final Duration DEFAULT_MIN_LOCK_AGE = Duration.ofMinutes(5);

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

  /**
   * Splits lock files into those that are safe to delete and those that must stay.
   *
   * <p>A lock file is only deletable when nothing holds its OS-level lock any more <em>and</em> it
   * is older than {@code minAge}. The first condition is the decisive one: the package cache
   * manager keeps an exclusive {@link FileLock} for as long as it works on a package, so a lock we
   * can acquire ourselves belongs to a process that died without cleaning up. The age is a second
   * guard for the short window between file creation and locking.
   */
  public static LockFileTriage triageLockFiles(List<Path> lockFiles, Duration minAge, Instant now)
      throws IOException {
    List<Path> deletable = new ArrayList<>();
    List<Path> retained = new ArrayList<>();
    for (Path lock : lockFiles) {
      if (isRecent(lock, minAge, now) || isLockHeld(lock)) {
        retained.add(lock);
      } else {
        deletable.add(lock);
      }
    }
    return new LockFileTriage(List.copyOf(deletable), List.copyOf(retained));
  }

  private static boolean isRecent(Path lock, Duration minAge, Instant now) throws IOException {
    Instant modified = Files.getLastModifiedTime(lock).toInstant();
    return modified.isAfter(now.minus(minAge));
  }

  /**
   * Whether some process currently holds the lock file. Anything that keeps us from answering the
   * question counts as held, so an unreadable or unusual lock file is never deleted.
   */
  static boolean isLockHeld(Path lock) {
    try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ,
        StandardOpenOption.WRITE)) {
      FileLock fileLock = channel.tryLock(0L, Long.MAX_VALUE, false);
      if (fileLock == null) {
        return true;
      }
      fileLock.release();
      return false;
    } catch (OverlappingFileLockException e) {
      return true;
    } catch (IOException e) {
      return true;
    }
  }

  public record LockFileTriage(List<Path> deletable, List<Path> retained) {

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
