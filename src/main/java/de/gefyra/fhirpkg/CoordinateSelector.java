package de.gefyra.fhirpkg;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class CoordinateSelector {

  private CoordinateSelector() {
  }

  static List<String> selectLatestCoordinatesByPackageId(Collection<String> coordinates) {
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
      if (isCandidateNewer(existing, parsed.version())) {
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

  static ParsedCoordinate parseCoordinate(String coordinate) {
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

  private static boolean isCandidateNewer(String existingVersion, String candidateVersion) {
    if (existingVersion == null || existingVersion.isBlank()) {
      return candidateVersion != null && !candidateVersion.isBlank();
    }
    if (candidateVersion == null || candidateVersion.isBlank()) {
      return false;
    }
    return compareSemVer(candidateVersion, existingVersion) > 0;
  }

  private static int compareSemVer(String left, String right) {
    if (left == null || right == null) {
      return Objects.compare(left, right, Comparator.nullsFirst(String::compareTo));
    }
    String[] leftParts = splitVersion(left);
    String[] rightParts = splitVersion(right);

    int coreCompare = compareCoreVersion(leftParts[0], rightParts[0]);
    if (coreCompare != 0) {
      return coreCompare;
    }
    return comparePreRelease(leftParts[1], rightParts[1]);
  }

  private static String[] splitVersion(String version) {
    String withoutBuild = version.split("\\+", 2)[0];
    String[] split = withoutBuild.split("-", 2);
    String core = split[0];
    String pre = split.length > 1 ? split[1] : null;
    return new String[]{core, pre};
  }

  private static int compareCoreVersion(String leftCore, String rightCore) {
    String[] left = leftCore.split("\\.");
    String[] right = rightCore.split("\\.");
    int max = Math.max(left.length, right.length);
    for (int i = 0; i < max; i++) {
      String l = i < left.length ? left[i] : "0";
      String r = i < right.length ? right[i] : "0";
      int cmp = compareIdentifier(l, r);
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }

  private static int comparePreRelease(String leftPre, String rightPre) {
    if (leftPre == null && rightPre == null) {
      return 0;
    }
    if (leftPre == null) {
      return 1;
    }
    if (rightPre == null) {
      return -1;
    }

    String[] left = leftPre.split("\\.");
    String[] right = rightPre.split("\\.");
    int max = Math.max(left.length, right.length);
    for (int i = 0; i < max; i++) {
      if (i >= left.length) {
        return -1;
      }
      if (i >= right.length) {
        return 1;
      }
      int cmp = compareIdentifier(left[i], right[i]);
      if (cmp != 0) {
        return cmp;
      }
    }
    return 0;
  }

  private static int compareIdentifier(String left, String right) {
    boolean leftNumeric = left.chars().allMatch(Character::isDigit);
    boolean rightNumeric = right.chars().allMatch(Character::isDigit);

    if (leftNumeric && rightNumeric) {
      return new BigInteger(left).compareTo(new BigInteger(right));
    }
    if (leftNumeric) {
      return -1;
    }
    if (rightNumeric) {
      return 1;
    }
    return left.compareToIgnoreCase(right);
  }

  record ParsedCoordinate(String name, String version) {
  }
}

