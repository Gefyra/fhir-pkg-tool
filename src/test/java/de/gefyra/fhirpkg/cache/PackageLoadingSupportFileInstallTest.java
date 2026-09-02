package de.gefyra.fhirpkg.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageLoadingSupportFileInstallTest {

  private static final String PKG_ID = "molit-service.fhir.vitu";
  private static final String PKG_VERSION = "0.1.20";

  @Test
  void installPackageFromFile_readsIdAndVersionFromTarballAndAddsItToCache(@TempDir Path tempDir)
      throws Exception {
    Path tgz = TestPackageTarballs.write(tempDir.resolve("molit-service.fhir.vitu-0.1.20.tgz"),
        "molit-service.fhir.vitu", "0.1.20");

    RecordingCacheManager cache = new RecordingCacheManager();
    NpmPackage installed =
        PackageLoadingSupport.installPackageFromFile(cache, tgz, new HashSet<>(), false);

    assertEquals("molit-service.fhir.vitu", cache.id);
    assertEquals("0.1.20", cache.version);
    assertEquals(tgz.toAbsolutePath().normalize().toString(), cache.source);
    assertTrue(cache.tgzBytes.length > 0);
    assertEquals("molit-service.fhir.vitu", installed.name());
  }

  @Test
  void installPackageFromFile_failsForMissingFile(@TempDir Path tempDir) {
    Path missing = tempDir.resolve("nope.tgz");
    IOException e = assertThrows(IOException.class,
        () -> PackageLoadingSupport.installPackageFromFile(new RecordingCacheManager(), missing,
            new HashSet<>(), false));
    assertTrue(e.getMessage().contains("does not exist"));
  }

  @Test
  void installPackageFromFile_keepsCachedPackageWhenNotForced(@TempDir Path tempDir)
      throws Exception {
    Path cacheDir = Files.createDirectory(tempDir.resolve("cache"));
    FilesystemPackageCacheManager cache = cacheManagerFor(cacheDir);
    Path first = TestPackageTarballs.write(tempDir.resolve("first.tgz"), PKG_ID, PKG_VERSION, "build-1");
    Path rebuilt = TestPackageTarballs.write(tempDir.resolve("rebuilt.tgz"), PKG_ID, PKG_VERSION,
        "build-2");

    PackageLoadingSupport.installPackageFromFile(cache, first, new HashSet<>(), false);
    PackageLoadingSupport.installPackageFromFile(cache, rebuilt, new HashSet<>(), false);

    assertEquals("build-1", cachedMarker(cacheDir));
  }

  @Test
  void installPackageFromFile_replacesCachedPackageWhenForced(@TempDir Path tempDir)
      throws Exception {
    Path cacheDir = Files.createDirectory(tempDir.resolve("cache"));
    FilesystemPackageCacheManager cache = cacheManagerFor(cacheDir);
    Path first = TestPackageTarballs.write(tempDir.resolve("first.tgz"), PKG_ID, PKG_VERSION, "build-1");
    Path rebuilt = TestPackageTarballs.write(tempDir.resolve("rebuilt.tgz"), PKG_ID, PKG_VERSION,
        "build-2");

    PackageLoadingSupport.installPackageFromFile(cache, first, new HashSet<>(), false);
    NpmPackage reinstalled =
        PackageLoadingSupport.installPackageFromFile(cache, rebuilt, new HashSet<>(), true);

    assertEquals("build-2", cachedMarker(cacheDir));
    assertEquals(PKG_ID, reinstalled.name());
    assertEquals(PKG_VERSION, reinstalled.version());
  }

  @Test
  void installPackageFromFile_forceInstallLeavesOtherCachedVersionsAlone(@TempDir Path tempDir)
      throws Exception {
    Path cacheDir = Files.createDirectory(tempDir.resolve("cache"));
    FilesystemPackageCacheManager cache = cacheManagerFor(cacheDir);
    Path other = TestPackageTarballs.write(tempDir.resolve("other.tgz"), PKG_ID, "0.1.19", "build-old");
    Path rebuilt = TestPackageTarballs.write(tempDir.resolve("rebuilt.tgz"), PKG_ID, PKG_VERSION,
        "build-2");

    PackageLoadingSupport.installPackageFromFile(cache, other, new HashSet<>(), false);
    PackageLoadingSupport.installPackageFromFile(cache, rebuilt, new HashSet<>(), true);

    assertTrue(Files.isDirectory(cacheDir.resolve(PKG_ID + "#0.1.19")));
    assertEquals("build-old", TestPackageTarballs.markerIn(cacheDir.resolve(PKG_ID + "#0.1.19")));
    assertEquals("build-2", cachedMarker(cacheDir));
  }

  @Test
  void installPackageFromFile_installsWhenNothingIsCachedYet(@TempDir Path tempDir)
      throws Exception {
    Path cacheDir = Files.createDirectory(tempDir.resolve("cache"));
    FilesystemPackageCacheManager cache = cacheManagerFor(cacheDir);
    Path tgz = TestPackageTarballs.write(tempDir.resolve("first.tgz"), PKG_ID, PKG_VERSION, "build-1");

    assertFalse(cache.packageInstalled(PKG_ID, PKG_VERSION));
    PackageLoadingSupport.installPackageFromFile(cache, tgz, new HashSet<>(), true);

    assertEquals("build-1", cachedMarker(cacheDir));
  }

  private static FilesystemPackageCacheManager cacheManagerFor(Path cacheDir) throws IOException {
    return new FilesystemPackageCacheManager.Builder().withCacheFolder(cacheDir.toString()).build();
  }

  private static String cachedMarker(Path cacheDir) throws IOException {
    return TestPackageTarballs.markerIn(cacheDir.resolve(PKG_ID + "#" + PKG_VERSION));
  }

  private static final class RecordingCacheManager implements IPackageCacheManager {

    private String id;
    private String version;
    private String source;
    private byte[] tgzBytes = new byte[0];

    @Override
    public NpmPackage addPackageToCache(String id, String version, InputStream tgz, String source)
        throws IOException {
      this.id = id;
      this.version = version;
      this.source = source;
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      tgz.transferTo(buffer);
      this.tgzBytes = buffer.toByteArray();

      NpmPackage pkg = NpmPackage.empty();
      JsonObject npm = new JsonObject();
      npm.add("name", id);
      npm.add("version", version);
      pkg.setNpm(npm);
      return pkg;
    }

    @Override
    public NpmPackage loadPackage(String name, String version) {
      throw new UnsupportedOperationException();
    }

    @Override
    public NpmPackage loadPackage(String name) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getPackageId(String canonicalUrl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getPackageUrl(String canonicalUrl) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getLatestVersion(String id, boolean useCache) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getLatestVersion(String id, String majorMinorVersion) {
      throw new UnsupportedOperationException();
    }

    @Override
    public NpmPackage loadPackageFromCacheOnly(String id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public NpmPackage loadPackageFromCacheOnly(String id, String version) {
      throw new UnsupportedOperationException();
    }
  }
}
