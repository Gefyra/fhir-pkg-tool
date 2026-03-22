package de.gefyra.fhirpkg;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class DependencyInputParser {

  private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

  private DependencyInputParser() {
  }

  static List<String> gatherPkgCoordsFromSushi(Path file, String inlineYaml) throws IOException {
    List<String> all = new ArrayList<>();
    if (file != null) {
      String text = Files.readString(file);
      all.addAll(parseSushiDepsYaml(text));
    }
    if (inlineYaml != null && !inlineYaml.isBlank()) {
      all.addAll(parseSushiDepsYaml(inlineYaml));
    }
    return all;
  }

  static List<String> gatherPkgCoordsFromPackageJson(Path packageJsonPath) throws IOException {
    if (packageJsonPath == null) {
      return List.of();
    }
    return parsePackageJsonDependencies(Files.readString(packageJsonPath));
  }

  static Optional<String> gatherFhirVersionFromSushi(Path file, String inlineYaml)
      throws IOException {
    if (file != null) {
      Optional<String> fromFile = parseSushiFhirVersion(Files.readString(file));
      if (fromFile.isPresent()) {
        return fromFile;
      }
    }
    if (inlineYaml != null && !inlineYaml.isBlank()) {
      return parseSushiFhirVersion(inlineYaml);
    }
    return Optional.empty();
  }

  static List<String> parseSushiDepsYaml(String yamlText) {
    if (yamlText == null || yamlText.isBlank()) {
      return List.of();
    }
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object root = yaml.load(yamlText);
    if (!(root instanceof Map<?, ?> map)) {
      return List.of();
    }

    if (!map.containsKey("dependencies")) {
      return List.of();
    }
    Object depsNode = map.get("dependencies");
    if (!(depsNode instanceof Map<?, ?> deps)) {
      return List.of();
    }
    return parseDependencyMap(deps, "sushi-config");
  }

  static List<String> parsePackageJsonDependencies(String jsonText) {
    if (jsonText == null || jsonText.isBlank()) {
      return List.of();
    }
    try {
      Map<String, Object> root = JSON_MAPPER.readValue(jsonText, new TypeReference<>() {
      });
      Object depsNode = root.get("dependencies");
      if (!(depsNode instanceof Map<?, ?> deps)) {
        return List.of();
      }
      return parseDependencyMap(deps, "package.json");
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid package.json content", e);
    }
  }

  static Optional<String> parseSushiFhirVersion(String yamlText) {
    if (yamlText == null || yamlText.isBlank()) {
      return Optional.empty();
    }
    Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    Object root = yaml.load(yamlText);
    if (!(root instanceof Map<?, ?> map)) {
      return Optional.empty();
    }
    Object raw = map.get("fhirVersion");
    return extractFhirVersion(raw);
  }

  private static List<String> parseDependencyMap(Map<?, ?> deps, String sourceName) {
    List<String> coords = new ArrayList<>();
    for (Map.Entry<?, ?> e : deps.entrySet()) {
      String pkgName = String.valueOf(e.getKey()).trim();
      if (pkgName.isBlank()) {
        continue;
      }

      String version = extractDependencyVersion(e.getValue());

      if (KnownProblematicPackages.isKnownProblematicPackage(pkgName, version)) {
        KnownProblematicPackages.logSkippingKnownProblematicPackage(sourceName, pkgName, version);
        continue;
      }

      coords.add((version == null || version.isBlank()) ? pkgName : (pkgName + "@" + version));
    }
    return coords;
  }

  private static String extractDependencyVersion(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof String s) {
      String version = s.trim();
      return version.isBlank() ? null : version;
    }
    if (value instanceof Map<?, ?> map) {
      Object ver = map.get("version");
      if (ver == null) {
        return null;
      }
      String version = String.valueOf(ver).trim();
      return version.isBlank() ? null : version;
    }
    String version = String.valueOf(value).trim();
    return version.isBlank() ? null : version;
  }

  private static Optional<String> extractFhirVersion(Object node) {
    if (node == null) {
      return Optional.empty();
    }
    if (node instanceof String s) {
      String trimmed = s.trim();
      return trimmed.isBlank() ? Optional.empty() : Optional.of(trimmed);
    }
    if (node instanceof Collection<?> coll) {
      for (Object entry : coll) {
        if (entry == null) {
          continue;
        }
        String trimmed = entry.toString().trim();
        if (!trimmed.isBlank()) {
          return Optional.of(trimmed);
        }
      }
      return Optional.empty();
    }
    if (node.getClass().isArray()) {
      int length = Array.getLength(node);
      for (int i = 0; i < length; i++) {
        Object entry = Array.get(node, i);
        if (entry == null) {
          continue;
        }
        String trimmed = entry.toString().trim();
        if (!trimmed.isBlank()) {
          return Optional.of(trimmed);
        }
      }
      return Optional.empty();
    }
    String fallback = node.toString().trim();
    return fallback.isBlank() ? Optional.empty() : Optional.of(fallback);
  }
}

