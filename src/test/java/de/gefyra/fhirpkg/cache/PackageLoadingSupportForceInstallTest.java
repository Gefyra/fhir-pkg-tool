package de.gefyra.fhirpkg.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers {@code --force-install} for registry coordinates. The tests exercise the cache drop
 * directly instead of a full load, because a load that misses the cache always queries the
 * ci-build server.
 */
class PackageLoadingSupportForceInstallTest {

  private static final String PKG_ID = "molit-service.fhir.vitu";
  private static final String PKG_VERSION = "0.1.20";
  private static final String COORDINATE = PKG_ID + "@" + PKG_VERSION;

  @Test
  void loadPackage_withoutForceServesTheCachedCopy(@TempDir Path tempDir) throws Exception {
    Path cacheDir = cacheWithInstalledPackage(tempDir);
    FilesystemPackageCacheManager cache = offlineCacheManager(cacheDir);

    NpmPackage loaded = PackageLoadingSupport.loadPackage(cache, COORDINATE, new HashSet<>());

    assertEquals(PKG_ID, loaded.name());
    assertEquals(PKG_VERSION, loaded.version());
    assertEquals("build-1", TestPackageTarballs.markerIn(packageDir(cacheDir)));
  }

  @Test
  void dropCachedPackage_removesTheCachedVersion(@TempDir Path tempDir) throws Exception {
    Path cacheDir = cacheWithInstalledPackage(tempDir);
    FilesystemPackageCacheManager cache = offlineCacheManager(cacheDir);

    PackageLoadingSupport.dropCachedPackageBeforeReinstall(cache, PKG_ID, PKG_VERSION, COORDINATE);

    assertFalse(Files.exists(packageDir(cacheDir)));
    assertFalse(cache.packageInstalled(PKG_ID, PKG_VERSION));
  }

  @Test
  void dropCachedPackage_keepsOtherCachedVersions(@TempDir Path tempDir) throws Exception {
    Path cacheDir = cacheWithInstalledPackage(tempDir);
    FilesystemPackageCacheManager cache = offlineCacheManager(cacheDir);
    Path other = TestPackageTarballs.write(tempDir.resolve("other.tgz"), PKG_ID, "0.1.19", "old");
    PackageLoadingSupport.installPackageFromFile(cache, other, new HashSet<>(), false);

    PackageLoadingSupport.dropCachedPackageBeforeReinstall(cache, PKG_ID, PKG_VERSION, COORDINATE);

    assertTrue(cache.packageInstalled(PKG_ID, "0.1.19"));
    assertEquals("old", TestPackageTarballs.markerIn(cacheDir.resolve(PKG_ID + "#0.1.19")));
  }

  @Test
  void dropCachedPackage_withoutVersionKeepsTheCachedCopy(@TempDir Path tempDir) throws Exception {
    Path cacheDir = cacheWithInstalledPackage(tempDir);
    FilesystemPackageCacheManager cache = offlineCacheManager(cacheDir);

    // Without a version there is no single cache entry to drop, so nothing may be removed.
    PackageLoadingSupport.dropCachedPackageBeforeReinstall(cache, PKG_ID, null, PKG_ID);

    assertTrue(cache.packageInstalled(PKG_ID, PKG_VERSION));
    assertEquals("build-1", TestPackageTarballs.markerIn(packageDir(cacheDir)));
  }

  @Test
  void dropCachedPackage_isANoOpForAnUncachedPackage(@TempDir Path tempDir) throws Exception {
    Path cacheDir = Files.createDirectory(tempDir.resolve("cache"));
    FilesystemPackageCacheManager cache = offlineCacheManager(cacheDir);

    PackageLoadingSupport.dropCachedPackageBeforeReinstall(cache, PKG_ID, PKG_VERSION, COORDINATE);

    assertFalse(cache.packageInstalled(PKG_ID, PKG_VERSION));
  }

  private static Path cacheWithInstalledPackage(Path tempDir) throws Exception {
    Path cacheDir = Files.createDirectory(tempDir.resolve("cache"));
    Path tgz =
        TestPackageTarballs.write(tempDir.resolve("first.tgz"), PKG_ID, PKG_VERSION, "build-1");
    PackageLoadingSupport.installPackageFromFile(offlineCacheManager(cacheDir), tgz, new HashSet<>(),
        false);
    return cacheDir;
  }

  private static Path packageDir(Path cacheDir) {
    return cacheDir.resolve(PKG_ID + "#" + PKG_VERSION);
  }

  private static FilesystemPackageCacheManager offlineCacheManager(Path cacheDir)
      throws IOException {
    return new FilesystemPackageCacheManager.Builder()
        .withCacheFolder(cacheDir.toString())
        .withPackageServers(List.of())
        .build();
  }
}
