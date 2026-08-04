package de.gefyra.fhirpkg.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.hl7.fhir.utilities.json.model.JsonObject;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageLoadingSupportFileInstallTest {

  @Test
  void installPackageFromFile_readsIdAndVersionFromTarballAndAddsItToCache(@TempDir Path tempDir)
      throws Exception {
    Path tgz = writePackageTarball(tempDir.resolve("molit-service.fhir.vitu-0.1.20.tgz"),
        "molit-service.fhir.vitu", "0.1.20");

    RecordingCacheManager cache = new RecordingCacheManager();
    NpmPackage installed = PackageLoadingSupport.installPackageFromFile(cache, tgz, new HashSet<>());

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
            new HashSet<>()));
    assertTrue(e.getMessage().contains("does not exist"));
  }

  private static Path writePackageTarball(Path target, String name, String version)
      throws IOException {
    String packageJson = "{\"name\":\"" + name + "\",\"version\":\"" + version
        + "\",\"fhirVersions\":[\"4.0.1\"],\"type\":\"fhir.ig\"}";
    byte[] payload = packageJson.getBytes(StandardCharsets.UTF_8);

    try (OutputStream fileOut = Files.newOutputStream(target);
        GZIPOutputStream gzip = new GZIPOutputStream(fileOut);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      TarArchiveEntry entry = new TarArchiveEntry("package/package.json");
      entry.setSize(payload.length);
      tar.putArchiveEntry(entry);
      tar.write(payload);
      tar.closeArchiveEntry();
    }
    return target;
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
