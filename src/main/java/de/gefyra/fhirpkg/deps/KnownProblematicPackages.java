package de.gefyra.fhirpkg.deps;

import java.util.Locale;

public final class KnownProblematicPackages {

  private static final String KNOWN_PROBLEMATIC_PACKAGE_NAME = "hl7.fhir.extensions.r5";
  private static final String KNOWN_PROBLEMATIC_PACKAGE_VERSION = "4.0.1";

  private KnownProblematicPackages() {
  }

  public static boolean isKnownProblematicCoordinate(String coordinate) {
    if (coordinate == null || coordinate.isBlank()) {
      return false;
    }
    String trimmed = coordinate.trim();
    int atIdx = trimmed.indexOf('@');
    int hashIdx = trimmed.indexOf('#');
    int splitIdx = atIdx >= 0 ? atIdx : hashIdx;
    if (splitIdx < 0) {
      return false;
    }
    String name = trimmed.substring(0, splitIdx).trim();
    String version = trimmed.substring(splitIdx + 1).trim();
    return isKnownProblematicPackage(name, version);
  }

  public static boolean isKnownProblematicPackage(String name, String version) {
    if (name == null || version == null) {
      return false;
    }
    return KNOWN_PROBLEMATIC_PACKAGE_NAME.equals(name.trim())
        && KNOWN_PROBLEMATIC_PACKAGE_VERSION.equals(version.trim());
  }

  public static void logSkippingKnownProblematicPackage(String source, String coordinate) {
    if (source == null || source.isBlank()) {
      System.out.printf(Locale.ROOT, "Skipping known problematic package: %s%n", coordinate);
      return;
    }
    System.out.printf(Locale.ROOT, "Skipping known problematic package from %s: %s%n", source,
        coordinate);
  }

  public static void logSkippingKnownProblematicPackage(String source, String name,
      String version) {
    String coord = (version == null || version.isBlank()) ? name : (name + "@" + version);
    logSkippingKnownProblematicPackage(source, coord);
  }
}
