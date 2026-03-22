package de.gefyra.fhirpkg.deps;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.hl7.fhir.utilities.VersionUtilities;

public final class CoordinateSelector {

  private CoordinateSelector() {
  }

  public static List<String> selectLatestCoordinatesByPackageId(Collection<String> coordinates) {
    if (coordinates == null || coordinates.isEmpty()) {
      return List.of();
    }

    LinkedHashMap<String, String> bestVersionByName = new LinkedHashMap<>();
    for (String coordinate : coordinates) {
      ParsedCoordinate parsed = parseCoordinate(coordinate);
      if (parsed == null || parsed.name().isBlank()) {
        continue;
      }

      String existing = bestVersionByName.get(parsed.name());
      if (existing == null) {
        bestVersionByName.put(parsed.name(), parsed.version());
        continue;
      }
      if (isCandidateNewer(parsed.name(), existing, parsed.version())) {
        bestVersionByName.put(parsed.name(), parsed.version());
      }
    }

    List<String> resolved = new ArrayList<>();
    for (Map.Entry<String, String> e : bestVersionByName.entrySet()) {
      String name = e.getKey();
      String version = e.getValue();
      resolved.add(version == null || version.isBlank() ? name : name + "@" + version);
    }
    return resolved;
  }

  private static ParsedCoordinate parseCoordinate(String coordinate) {
    if (coordinate == null || coordinate.isBlank()) {
      return null;
    }
    String trimmed = coordinate.trim();
    int atIdx = trimmed.indexOf('@');
    int hashIdx = trimmed.indexOf('#');
    int idx;
    if (atIdx < 0) {
      idx = hashIdx;
    } else if (hashIdx < 0) {
      idx = atIdx;
    } else {
      idx = Math.min(atIdx, hashIdx);
    }

    if (idx < 0) {
      return new ParsedCoordinate(trimmed, null);
    }
    String name = trimmed.substring(0, idx).trim();
    String version = trimmed.substring(idx + 1).trim();
    if (version.isBlank()) {
      version = null;
    }
    return new ParsedCoordinate(name, version);
  }

  private static boolean isCandidateNewer(String packageName, String existingVersion,
      String candidateVersion) {
    if (existingVersion == null || existingVersion.isBlank()) {
      return candidateVersion != null && !candidateVersion.isBlank();
    }
    if (candidateVersion == null || candidateVersion.isBlank()) {
      return false;
    }
    return compareVersionsStrict(packageName, candidateVersion, existingVersion) > 0;
  }

  private static int compareVersionsStrict(String packageName, String left, String right) {
    try {
      return VersionUtilities.compareVersions(left, right);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Invalid package version for '" + packageName + "': '" + left + "' vs '" + right + "'",
          e);
    }
  }

  private record ParsedCoordinate(String name, String version) {
  }
}
