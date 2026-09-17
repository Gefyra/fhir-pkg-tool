package de.gefyra.fhirpkg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import de.gefyra.fhirpkg.cache.CacheSafety;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class FhirPackageSnapshotToolCacheSafetyTest {

  @Test
  void parseCacheVersionFromIni_readsCacheSectionVersion() {
    String ini = """
        [cache]
        version=3
        [other]
        foo=bar
        """;

    Optional<String> result = FhirPackageSnapshotTool.parseCacheVersionFromIni(ini);

    assertEquals(Optional.of("3"), result);
  }

  @Test
  void parseCacheVersionFromIni_returnsEmptyWhenCacheVersionMissing() {
    String ini = """
        [cache]
        somethingElse=1
        """;

    Optional<String> result = FhirPackageSnapshotTool.parseCacheVersionFromIni(ini);

    assertEquals(Optional.empty(), result);
  }

  @Test
  void isSupportedCacheVersion_acceptsVersion4AndRejectsOthers() {
    assertTrue(FhirPackageSnapshotTool.isSupportedCacheVersion("4"));
    assertTrue(FhirPackageSnapshotTool.isSupportedCacheVersion(" 4 "));
    assertFalse(FhirPackageSnapshotTool.isSupportedCacheVersion("3"));
    assertFalse(FhirPackageSnapshotTool.isSupportedCacheVersion(""));
  }

  @Test
  void shouldValidateDefaultCacheSafety_trueForDefaultAndFalseForCustomPath() {
    Path defaultDir = FhirPackageSnapshotTool.defaultCacheDir().toAbsolutePath().normalize();
    Path customDir = defaultDir.resolve("custom-output");
    assertTrue(FhirPackageSnapshotTool.shouldValidateDefaultCacheSafety(defaultDir));
    assertFalse(FhirPackageSnapshotTool.shouldValidateDefaultCacheSafety(customDir));
  }

  @Test
  void call_checksLockFilesAlsoForCustomOut(@TempDir Path tempDir) throws Exception {
    Path out = tempDir.resolve("custom-out");
    Files.createDirectories(out);
    Files.writeString(out.resolve("test.lock"), "locked");

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = out;

    int exit = tool.call();

    assertEquals(5, exit);
  }

  @Test
  void call_repairsOrphanedLockFileByDefault(@TempDir Path tempDir) throws Exception {
    Path out = tempDir.resolve("custom-out");
    Files.createDirectories(out);
    Path lock = out.resolve("orphan.lock");
    Files.writeString(lock, "locked");
    age(lock, Duration.ofHours(1));

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = out;

    int exit = tool.call();

    assertEquals(2, exit, "an orphaned lock must not block the run any more");
    assertFalse(Files.exists(lock));
  }

  @Test
  void call_noRepairLockFilesKeepsOrphanedLockAndAborts(@TempDir Path tempDir) throws Exception {
    Path out = tempDir.resolve("custom-out");
    Files.createDirectories(out);
    Path lock = out.resolve("orphan.lock");
    Files.writeString(lock, "locked");
    age(lock, Duration.ofHours(1));

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = out;
    tool.noRepairLockFiles = true;

    int exit = tool.call();

    assertEquals(5, exit);
    assertTrue(Files.exists(lock), "opting out must leave every lock file untouched");
  }

  @Test
  void call_repairLockFilesWorksAlsoForCustomOut(@TempDir Path tempDir) throws Exception {
    Path out = tempDir.resolve("custom-out");
    Files.createDirectories(out);
    Path lock = out.resolve("repair.lock");
    Files.writeString(lock, "locked");
    age(lock, Duration.ofHours(1));

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = out;

    int exit = tool.call();

    assertEquals(2, exit);
    assertFalse(Files.exists(lock));
  }

  @Test
  void call_repairKeepsRecentLockFileAndAborts(@TempDir Path tempDir) throws Exception {
    Path out = tempDir.resolve("custom-out");
    Files.createDirectories(out);
    Path lock = out.resolve("fresh.lock");
    Files.writeString(lock, "locked");

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = out;

    int exit = tool.call();

    assertEquals(5, exit);
    assertTrue(Files.exists(lock), "a lock file younger than the threshold must survive repair");
  }

  @Test
  void call_repairKeepsLockFileHeldByAnotherProcessAndAborts(@TempDir Path tempDir)
      throws Exception {
    Path out = tempDir.resolve("custom-out");
    Files.createDirectories(out);
    Path lock = out.resolve("held.lock");
    Files.writeString(lock, "locked");
    age(lock, Duration.ofHours(1));

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = out;

    int exit;
    try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ,
        StandardOpenOption.WRITE); FileLock held = channel.lock()) {
      assertTrue(held.isValid());
      exit = tool.call();
    }

    assertEquals(5, exit);
    assertTrue(Files.exists(lock), "a held lock file must survive repair even when it is old");
  }

  @Test
  void triageLockFiles_separatesStaleFromRetained(@TempDir Path tempDir) throws Exception {
    Path stale = tempDir.resolve("stale.lock");
    Path fresh = tempDir.resolve("fresh.lock");
    Files.writeString(stale, "x");
    Files.writeString(fresh, "x");
    age(stale, Duration.ofHours(1));

    CacheSafety.LockFileTriage triage = CacheSafety.triageLockFiles(List.of(stale, fresh),
        CacheSafety.DEFAULT_MIN_LOCK_AGE, Instant.now());

    assertEquals(List.of(stale), triage.deletable());
    assertEquals(List.of(fresh), triage.retained());
  }

  @Test
  void triageLockFiles_treatsHeldLockAsRetained(@TempDir Path tempDir) throws Exception {
    Path lock = tempDir.resolve("held.lock");
    Files.writeString(lock, "x");
    age(lock, Duration.ofHours(1));

    try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ,
        StandardOpenOption.WRITE); FileLock held = channel.lock()) {
      assertTrue(held.isValid());

      CacheSafety.LockFileTriage triage = CacheSafety.triageLockFiles(List.of(lock),
          CacheSafety.DEFAULT_MIN_LOCK_AGE, Instant.now());

      assertTrue(triage.deletable().isEmpty());
      assertEquals(List.of(lock), triage.retained());
    }
  }

  @Test
  void call_missingPackagesIniOnCustomOutIsAllowed(@TempDir Path tempDir) throws Exception {
    Path out = tempDir.resolve("custom-out");

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = out;

    int exit = tool.call();

    assertEquals(2, exit);
  }

  @Test
  void call_invalidPackagesIniOnCustomOutFails(@TempDir Path tempDir) throws Exception {
    Path out = tempDir.resolve("custom-out");
    Files.createDirectories(out);
    Files.writeString(out.resolve("packages.ini"), """
        [cache]
        version = 3
        """);

    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    tool.outDir = out;

    int exit = tool.call();

    assertEquals(4, exit);
  }

  @Test
  void lockRepairIsOnUnlessExplicitlyDisabled() {
    assertFalse(parse().noRepairLockFiles, "repair must be on without any flag");
    assertTrue(parse("--no-repair-lock-files").noRepairLockFiles);
    // The legacy opt-in must never switch the repair off - that is what picocli's negatable
    // options would have done with an inverted default.
    assertFalse(parse("--repair-lock-files").noRepairLockFiles);
  }

  private static FhirPackageSnapshotTool parse(String... args) {
    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    new CommandLine(tool).parseArgs(args);
    return tool;
  }

  private static void age(Path file, Duration by) throws Exception {
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(by)));
  }
}
