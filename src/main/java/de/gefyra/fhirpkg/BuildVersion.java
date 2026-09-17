package de.gefyra.fhirpkg;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import picocli.CommandLine.IVersionProvider;

/**
 * Supplies the version shown by {@code --version}. The value is filtered into
 * {@code fhir-pkg-tool-version.properties} at build time from the Maven {@code revision} property,
 * which the release workflow sets to the git tag.
 */
public final class BuildVersion implements IVersionProvider {

  private static final String RESOURCE = "/fhir-pkg-tool-version.properties";
  private static final String UNKNOWN = "unknown";

  public static String current() {
    try (InputStream in = BuildVersion.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        return UNKNOWN;
      }
      Properties properties = new Properties();
      properties.load(in);
      String version = properties.getProperty("version");
      if (version == null || version.isBlank() || version.startsWith("${")) {
        return UNKNOWN;
      }
      return version.trim();
    } catch (IOException e) {
      return UNKNOWN;
    }
  }

  @Override
  public String[] getVersion() {
    return new String[]{"fhir-pkg-tool " + current()};
  }
}
