package de.gefyra.fhirpkg.testsupport;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/** Builds minimal FHIR NPM package tarballs for tests. */
public final class TestPackageTarball {

  private TestPackageTarball() {
  }

  /** A profile on Patient; its snapshot can only be built when a core package is in context. */
  public static final String PATIENT_PROFILE = """
      {
        "resourceType": "StructureDefinition",
        "id": "DemoPatient",
        "url": "http://example.org/demo/StructureDefinition/DemoPatient",
        "name": "DemoPatient",
        "status": "active",
        "fhirVersion": "4.0.1",
        "kind": "resource",
        "abstract": false,
        "type": "Patient",
        "baseDefinition": "http://hl7.org/fhir/StructureDefinition/Patient",
        "derivation": "constraint",
        "differential": {
          "element": [ { "id": "Patient.birthDate", "path": "Patient.birthDate", "min": 1 } ]
        }
      }
      """;

  public static Path write(Path target, String name, String version, Map<String, String> resources)
      throws IOException {
    String packageJson = "{\"name\":\"" + name + "\",\"version\":\"" + version
        + "\",\"fhirVersions\":[\"4.0.1\"],\"type\":\"fhir.ig\","
        + "\"canonical\":\"http://example.org/demo\",\"dependencies\":{}}";

    try (OutputStream fileOut = Files.newOutputStream(target);
        GZIPOutputStream gzip = new GZIPOutputStream(fileOut);
        TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
      addEntry(tar, "package/package.json", packageJson);
      for (Map.Entry<String, String> resource : resources.entrySet()) {
        addEntry(tar, "package/" + resource.getKey(), resource.getValue());
      }
    }
    return target;
  }

  private static void addEntry(TarArchiveOutputStream tar, String path, String content)
      throws IOException {
    byte[] payload = content.getBytes(StandardCharsets.UTF_8);
    TarArchiveEntry entry = new TarArchiveEntry(path);
    entry.setSize(payload.length);
    tar.putArchiveEntry(entry);
    tar.write(payload);
    tar.closeArchiveEntry();
  }
}
