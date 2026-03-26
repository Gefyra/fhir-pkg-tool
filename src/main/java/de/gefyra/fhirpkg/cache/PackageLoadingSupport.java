package de.gefyra.fhirpkg.cache;

import de.gefyra.fhirpkg.common.ExceptionSummary;
import de.gefyra.fhirpkg.deps.KnownProblematicPackages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;

public final class PackageLoadingSupport {

  private PackageLoadingSupport() {
  }

  public static Set<Path> initKnownCacheDirs(Path cacheDir) {
    Set<Path> known = new HashSet<>();
    if (cacheDir == null) {
      return known;
    }
    try {
      if (Files.exists(cacheDir) && Files.isDirectory(cacheDir)) {
        try (var stream = Files.list(cacheDir)) {
          for (Path entry : (Iterable<Path>) stream::iterator) {
            if (Files.isDirectory(entry)) {
              known.add(entry.toAbsolutePath().normalize());
            }
          }
        }
      }
    } catch (IOException e) {
      System.err.printf(Locale.ROOT, "Failed to scan cache directory %s: %s%n", cacheDir,
          e.getMessage());
    }
    return known;
  }

  public static NpmPackage loadPackage(IPackageCacheManager cache, String coordinate,
      Set<Path> knownCacheDirs) throws IOException {
    String name = coordinate;
    String version = null;
    if (coordinate.contains("@")) {
      String[] parts = coordinate.split("@", 2);
      name = parts[0];
      version = parts[1];
    }
    NpmPackage pkg = (version == null || version.isBlank())
        ? cache.loadPackage(name)
        : cache.loadPackage(name, version);
    notePackageCacheLocation(pkg, knownCacheDirs);
    return pkg;
  }

  public static List<NpmPackage> loadAllDependencies(IPackageCacheManager cache, NpmPackage root,
      Set<String> seenPackageKeys, Set<Path> knownCacheDirs) {
    List<String> deps = root.dependencies();
    if (deps == null || deps.isEmpty()) {
      return List.of();
    }

    List<NpmPackage> out = new ArrayList<>();
    for (String entry : deps) {
      String name = entry;
      String version = null;
      int hashIdx = entry.indexOf('#');
      if (hashIdx >= 0) {
        name = entry.substring(0, hashIdx);
        version = entry.substring(hashIdx + 1);
      }

      if (KnownProblematicPackages.isKnownProblematicPackage(name, version)) {
        KnownProblematicPackages.logSkippingKnownProblematicPackage("resolved dependencies", name,
            version);
        continue;
      }

      if (version != null && !version.isBlank() && seenPackageKeys.contains(packageKey(name, version))) {
        continue;
      }

      try {
        NpmPackage p = (version == null || version.isBlank())
            ? cache.loadPackage(name)
            : cache.loadPackage(name, version);
        String loadedKey = packageKey(p.name(), p.version());
        if (!seenPackageKeys.add(loadedKey)) {
          continue;
        }
        notePackageCacheLocation(p, knownCacheDirs);
        out.add(p);
        out.addAll(loadAllDependencies(cache, p, seenPackageKeys, knownCacheDirs));
      } catch (Exception e) {
        String coordinate = version == null || version.isBlank() ? name : name + "#" + version;
        System.err.printf(Locale.ROOT, "Failed to install dependency %s (%s). Continuing.%n",
            coordinate, ExceptionSummary.summarizeException(e));
      }
    }
    return out;
  }

  public static String packageKey(String name, String version) {
    String normalizedName = Optional.ofNullable(name).orElse("").trim();
    String normalizedVersion = Optional.ofNullable(version).orElse("").trim();
    return normalizedName + "#" + normalizedVersion;
  }

  private static void notePackageCacheLocation(NpmPackage pkg, Set<Path> knownCacheDirs) {
    if (pkg == null || knownCacheDirs == null) {
      return;
    }
    String rawPath = pkg.getPath();
    if (rawPath == null || rawPath.isBlank()) {
      return;
    }
    try {
      Path resolved = Paths.get(rawPath).toAbsolutePath().normalize();
      if (knownCacheDirs.add(resolved)) {
        System.out.printf(Locale.ROOT,
            "Installed package %s#%s at %s%n",
            Optional.ofNullable(pkg.name()).orElse(""),
            Optional.ofNullable(pkg.version()).orElse(""),
            resolved);
      }
    } catch (Exception e) {
      System.err.printf(Locale.ROOT,
          "Installed package %s#%s but resolved cache path failed: %s%n",
          Optional.ofNullable(pkg.name()).orElse(""),
          Optional.ofNullable(pkg.version()).orElse(""),
          e.getMessage());
    }
  }
}
