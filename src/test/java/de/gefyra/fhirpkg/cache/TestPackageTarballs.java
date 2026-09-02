package de.gefyra.fhirpkg.cache;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.hl7.fhir.utilities.json.parser.JsonParser;

/** Minimal FHIR package tarballs for the package loading tests. */
final class TestPackageTarballs {

  private TestPackageTarballs() {
  }

  static Path write(Path target, String name, String version) throws IOException {
    return write(target, name, version, "unmarked");
  }

  /**
   * Writes a tarball whose {@code date} field carries {@code marker}, so tests can tell two builds
   * of the same {@code <id>#<version>} apart.
   */
  static Path write(Path target, String name, String version, String marker) throws IOException {
    String packageJson = "{\"name\":\"" + name + "\",\"version\":\"" + version
        + "\",\"fhirVersions\":[\"4.0.1\"],\"type\":\"fhir.ig\",\"date\":\"" + marker + "\"}";
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

  /** Reads back the {@code date} marker of an installed package. */
  static String markerIn(Path packageDir) throws IOException {
    String json = Files.readString(packageDir.resolve("package").resolve("package.json"),
        StandardCharsets.UTF_8);
    return JsonParser.parseObject(json).asString("date");
  }
}
