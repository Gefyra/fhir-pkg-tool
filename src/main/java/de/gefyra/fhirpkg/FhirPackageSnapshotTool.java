package de.gefyra.fhirpkg;

import de.gefyra.fhirpkg.cache.CacheSafety;
import de.gefyra.fhirpkg.cache.PackageLoadingSupport;
import de.gefyra.fhirpkg.common.ExceptionSummary;
import de.gefyra.fhirpkg.deps.CoordinateSelector;
import de.gefyra.fhirpkg.deps.DependencyInputParser;
import de.gefyra.fhirpkg.deps.KnownProblematicPackages;
import de.gefyra.fhirpkg.json.JsonFieldExtractor;
import de.gefyra.fhirpkg.snapshot.SnapshotSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.PackageServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * CLI tool that downloads FHIR NPM packages, resolves dependencies, generates StructureDefinition
 * snapshots, and writes them as JSON files.
 */
@Command(
    name = "fhir-pkg-tool",
    mixinStandardHelpOptions = true,
    version = "1.0-SNAPSHOT",
    description = "Downloads FHIR NPM packages, resolves dependencies, generates StructureDefinition snapshots, and writes them as JSON files."
)
public class FhirPackageSnapshotTool implements Callable<Integer> {

  // Look for a top-level field named "snapshot"; avoid '{' to sidestep JDK21 preview parsing issues.
  private static final Pattern SNAPSHOT_FIELD = Pattern.compile("\\\"snapshot\\\"\\s*:",
      Pattern.DOTALL);

  @Option(names = {"-p", "--package"},
      description = "FHIR NPM packages (repeatable or comma-separated; e.g. hl7.fhir.r4.core@4.0.1,hl7.fhir.us.core@6.1.0)")
  List<String> pkgCoordinates = new ArrayList<>();

  @Option(names = {
      "--sushi-deps-str"}, description = "YAML block (as string) from sushi-config.yaml with 'dependencies:'")
  String sushiDepsStr;

  @Option(names = {
      "--sushi-deps-file"}, description = "Path to sushi-config.yaml (or a file containing the YAML dependencies)")
  Path sushiDepsFile;

  @Option(names = {
      "--package-json-file"}, description = "Path to package.json (only 'dependencies' are read; 'devDependencies' are ignored)")
  Path packageJsonFile;

  public static Path defaultOutputDir() {
    return defaultCacheDir();
  }

  @Option(names = {"-o",
      "--out"}, description = "Output directory for StructureDefinitions (default: ~/.fhir/packages; Windows: C:\\Users\\<USER>\\.fhir\\packages)")
  Path outDir = defaultOutputDir();

  public static Path defaultCacheDir() {
    String githubActions = System.getenv("GITHUB_ACTIONS");
    if ("true".equals(githubActions)) {
      String home = System.getenv("HOME");
      if (home != null && !home.isBlank()) {
        return Paths.get(home, ".fhir", "packages");
      }
    }
    return Paths.get(System.getProperty("user.home"), ".fhir", "packages");
  }

  @Option(names = {
      "--registry"}, description = "Package registry (default: https://packages.fhir.org)")
  String registryUrl = "https://packages.fhir.org";

  @Option(names = {"--skip-deps"}, description = "Do NOT automatically load dependencies")
  boolean skipDependencies = false;

  @Option(names = {"--overwrite"}, description = "Overwrite existing files")
  boolean overwrite = false;

  @Option(names = {"--pretty"}, description = "Pretty-print JSON")
  boolean pretty = true;

  @Option(names = {
      "--force-snapshot"}, description = "Always (re)generate snapshots, even if present")
  boolean forceSnapshot = false;

  @Option(names = {
      "--profiles-dir"}, description = "Directory with local StructureDefinition JSONs (processed recursively)")
  Path profilesDir;

  @Option(names = {
      "--repair-lock-files"}, description = "Delete '*.lock' files in effective cache directory before package loading")
  boolean repairLockFiles = false;

  @Option(names = {"--debug"}, description = "Print stack traces for execution errors")
  boolean debug = false;

  public static void main(String[] args) {
    System.out.println("FHIR Package Tool starting...");
    if (!hasDebugFlag(args)) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
    }
    FhirPackageSnapshotTool tool = new FhirPackageSnapshotTool();
    CommandLine commandLine = new CommandLine(tool);
    commandLine.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
      System.err.printf(Locale.ROOT, "Error: %s%n", summarizeException(ex));
      if (tool.debug) {
        ex.printStackTrace(System.err);
      }
      return 1;
    });
    int exit = commandLine.execute(args);
    System.exit(exit);
  }

  static boolean hasDebugFlag(String[] args) {
    if (args == null || args.length == 0) {
      return false;
    }
    for (String arg : args) {
      if ("--debug".equals(arg)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Integer call() throws Exception {
    Path effectiveOutDir = outDir.toAbsolutePath().normalize();
    Path effectiveCacheDir = effectiveOutDir;

    printConfiguration(effectiveOutDir, effectiveCacheDir);

    int cacheSafetyExitCode = validateCacheSafetyIfNeeded(effectiveCacheDir);
    if (cacheSafetyExitCode != 0) {
      return cacheSafetyExitCode;
    }

    Files.createDirectories(effectiveOutDir);

    Set<String> requested = collectRequestedCoordinates();
    List<String> resolvedRequested = selectLatestCoordinatesByPackageId(requested);
    printRequestedPackages(resolvedRequested);

    Optional<String> sushiFhirVersion = DependencyInputParser.gatherFhirVersionFromSushi(
        sushiDepsFile, sushiDepsStr);

    if (resolvedRequested.isEmpty() && profilesDir == null) {
      if (sushiDepsFile != null || sushiDepsStr != null || packageJsonFile != null) {
        System.out.println(
            "No dependencies found in provided dependency sources, skipping package installation.");
        return 0;
      }
      System.err.println(
          "No packages specified. Use -p, --sushi-deps-*, or --package-json-file (or provide --profiles-dir). Aborting.");
      return 2;
    }

    Files.createDirectories(effectiveCacheDir);
    IPackageCacheManager cache = buildCache(effectiveCacheDir);
    Set<Path> knownCacheDirs = PackageLoadingSupport.initKnownCacheDirs(effectiveCacheDir);

    List<NpmPackage> allPkgs = loadRequestedAndDependencyPackages(cache, resolvedRequested,
        knownCacheDirs);
    if (allPkgs.isEmpty()) {
      if (profilesDir == null) {
        System.err.println("No packages loaded – aborting.");
        return 3;
      }
      System.out.println("No FHIR packages loaded; continuing with local profiles only.");
    }

    String selectedFhirVersion = selectFhirVersion(sushiFhirVersion, allPkgs);
    SnapshotSupport.FhirRelease release = SnapshotSupport.resolveFhirRelease(selectedFhirVersion);
    SnapshotSupport.SnapshotEngine snapshotEngine = SnapshotSupport.createSnapshotEngine(release,
        allPkgs);

    PackageStats packageStats = new PackageStats();
    if (profilesDir == null) {
      packageStats = processPackageOutputs(allPkgs, effectiveOutDir, snapshotEngine);
    }

    LocalStats localStats = processLocalProfiles(snapshotEngine, effectiveOutDir);

    System.out.printf(Locale.ROOT,
        "Done: %d SDs found, %d snapshots generated, %d SD files written, %d files copied. Local: %d SDs, %d generated, %d written.%n",
        packageStats.total, packageStats.generated, packageStats.sdWritten, packageStats.filesCopied,
        localStats.total, localStats.generated, localStats.written);
    System.out.printf(Locale.ROOT, "Output directory: %s%n", effectiveOutDir);
    System.out.printf(Locale.ROOT, "Cache directory: %s%n", effectiveCacheDir);
    return 0;
  }

  private void printConfiguration(Path effectiveOutDir, Path effectiveCacheDir) {
    boolean defaultPath = shouldValidateDefaultCacheSafety(effectiveCacheDir);
    System.out.println("FHIR Package Tool Configuration:");
    System.out.println("  Configured output directory: " + outDir.toAbsolutePath().normalize());
    System.out.println("  Effective output directory: " + effectiveOutDir);
    System.out.println("  Effective cache directory: " + effectiveCacheDir);
    System.out.println(
        "  Cache safety checks: enabled" + (defaultPath ? " (default cache path)" : " (--out path)")
    );

    String githubActions = System.getenv("GITHUB_ACTIONS");
    if ("true".equals(githubActions)) {
      System.out.println("  GitHub Actions detected (GITHUB_ACTIONS=true)");
      System.out.println("  HOME environment variable: " + System.getenv("HOME"));
      System.out.println("  user.home system property: " + System.getProperty("user.home"));
    } else {
      System.out.println("  Running in local environment");
    }
    System.out.println();
  }

  private int validateCacheSafetyIfNeeded(Path effectiveCacheDir) {
    Optional<String> cacheVersion;
    try {
      cacheVersion = CacheSafety.readCacheVersionFromCacheIni(effectiveCacheDir);
    } catch (IOException e) {
      System.err.printf(Locale.ROOT, "Error: Unable to read cache metadata from %s (%s). Aborting.%n",
          effectiveCacheDir.resolve("packages.ini"), e.getMessage());
      return 4;
    }
    if (cacheVersion.isPresent()) {
      String detectedVersion = cacheVersion.get();
      if (!isSupportedCacheVersion(detectedVersion)) {
        System.err.printf(Locale.ROOT,
            "Error: Unsupported cache metadata version '%s' in packages.ini. Expected '%s'. Aborting.%n",
            detectedVersion, CacheSafety.expectedCacheMetadataVersion());
        return 4;
      }
      System.out.println("Detected cache metadata version in packages.ini: " + detectedVersion);
    } else if (Files.exists(effectiveCacheDir.resolve("packages.ini"))) {
      System.err.println(
          "Error: packages.ini found but [cache]/version could not be parsed. Aborting to avoid unsafe cache operations.");
      return 4;
    }

    List<Path> lockFiles;
    try {
      lockFiles = CacheSafety.findLockFiles(effectiveCacheDir);
    } catch (IOException e) {
      System.err.printf(Locale.ROOT, "Error: Failed to list lock files in %s (%s). Aborting.%n",
          effectiveCacheDir, e.getMessage());
      return 5;
    }
    if (lockFiles.isEmpty()) {
      return 0;
    }
    System.err.printf(Locale.ROOT, "Error: Found %d .lock file(s) in cache.%n", lockFiles.size());
    if (!repairLockFiles) {
      System.err.println("Hint: rerun with --repair-lock-files to remove stale lock files.");
      return 5;
    }
    try {
      int deleted = CacheSafety.deleteLockFiles(lockFiles);
      System.out.printf(Locale.ROOT, "Repair: deleted %d .lock file(s).%n", deleted);
      List<Path> remainingLocks = CacheSafety.findLockFiles(effectiveCacheDir);
      if (!remainingLocks.isEmpty()) {
        System.err.printf(Locale.ROOT, "Error: %d .lock file(s) remain after repair. Aborting.%n",
            remainingLocks.size());
        return 5;
      }
      return 0;
    } catch (IOException e) {
      System.err.printf(Locale.ROOT, "Error: Failed to delete lock files (%s). Aborting.%n",
          e.getMessage());
      return 5;
    }
  }

  private Set<String> collectRequestedCoordinates() throws IOException {
    Set<String> requested = new LinkedHashSet<>();
    if (pkgCoordinates != null) {
      for (String s : pkgCoordinates) {
        if (s == null) {
          continue;
        }
        Arrays.stream(s.split(","))
            .map(String::trim)
            .filter(x -> !x.isBlank())
            .forEach(requested::add);
      }
    }
    requested.addAll(DependencyInputParser.gatherPkgCoordsFromSushi(sushiDepsFile, sushiDepsStr));
    requested.addAll(DependencyInputParser.gatherPkgCoordsFromPackageJson(packageJsonFile));
    return requested;
  }

  private void printRequestedPackages(List<String> resolvedRequested) {
    if (resolvedRequested.isEmpty()) {
      return;
    }
    System.out.println("Requested packages:");
    for (String pkg : resolvedRequested) {
      System.out.println("  - " + pkg);
    }
  }

  private IPackageCacheManager buildCache(Path effectiveCacheDir) throws Exception {
    FilesystemPackageCacheManager.Builder cacheBuilder = new FilesystemPackageCacheManager.Builder()
        .withCacheFolder(effectiveCacheDir.toString())
        .withPackageServers(List.of(new PackageServer(registryUrl)));
    return cacheBuilder.build();
  }

  private List<NpmPackage> loadRequestedAndDependencyPackages(IPackageCacheManager cache,
      List<String> resolvedRequested, Set<Path> knownCacheDirs) throws IOException {
    List<NpmPackage> allPkgs = new ArrayList<>();
    Set<String> seenByName = new HashSet<>();
    for (String coord : resolvedRequested) {
      if (KnownProblematicPackages.isKnownProblematicCoordinate(coord)) {
        KnownProblematicPackages.logSkippingKnownProblematicPackage("requested packages", coord);
        continue;
      }
      NpmPackage p = PackageLoadingSupport.loadPackage(cache, coord, knownCacheDirs);
      if (seenByName.add(p.name())) {
        allPkgs.add(p);
      }
    }

    if (!skipDependencies) {
      for (int i = 0; i < allPkgs.size(); i++) {
        NpmPackage root = allPkgs.get(i);
        List<NpmPackage> deps = PackageLoadingSupport.loadAllDependencies(cache, root, seenByName,
            knownCacheDirs);
        allPkgs.addAll(deps);
      }
    }
    return allPkgs;
  }

  private String selectFhirVersion(Optional<String> sushiFhirVersion, List<NpmPackage> allPkgs) {
    if (sushiFhirVersion.isPresent()) {
      return sushiFhirVersion.get();
    }
    if (!allPkgs.isEmpty()) {
      return allPkgs.get(0).fhirVersion();
    }
    System.err.println("No FHIR version available; defaulting to R5 context.");
    return null;
  }

  private PackageStats processPackageOutputs(List<NpmPackage> allPkgs, Path effectiveOutDir,
      SnapshotSupport.SnapshotEngine snapshotEngine) throws Exception {
    PackageStats stats = new PackageStats();
    for (NpmPackage p : allPkgs) {
      String pkgFolderName = p.name() + "#" + p.version();
      Path pkgOutDir = effectiveOutDir.resolve(pkgFolderName);

      for (Map.Entry<String, NpmPackage.NpmPackageFolder> entry : p.getFolders().entrySet()) {
        String folderName = entry.getKey();
        NpmPackage.NpmPackageFolder folder = entry.getValue();
        Path folderOut = pkgOutDir.resolve(folderName);
        Files.createDirectories(folderOut);
        for (String fname : folder.listFiles()) {
          Path target = folderOut.resolve(fname);
          if (!overwrite && Files.exists(target)) {
            continue;
          }
          try (InputStream is = p.load(folderName, fname)) {
            if (is == null) {
              continue;
            }
            byte[] bytes = is.readAllBytes();
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            stats.filesCopied++;
          }
        }
      }

      for (String resName : p.listResources("StructureDefinition")) {
        stats.total++;
        try (InputStream is = p.load("package", resName)) {
          if (is == null) {
            continue;
          }
          String json = new String(is.readAllBytes());
          JsonFieldExtractor.ProfileFields profileFields = JsonFieldExtractor.extractProfileFields(
              json);

          boolean hasSnapshot = SNAPSHOT_FIELD.matcher(json).find();
          boolean didGenerate = forceSnapshot || !hasSnapshot;
          if (didGenerate) {
            json = snapshotEngine.generateSnapshot(json, pretty,
                profileFields.url(), profileFields.name());
            stats.generated++;
            if (debug) {
              System.out.printf(Locale.ROOT, "Generated snapshot: %s#%s/%s%n", p.name(), p.version(),
                  resName);
            }
          }

          Path target = pkgOutDir.resolve("package").resolve(resName);
          if (!didGenerate && !overwrite && Files.exists(target)) {
            continue;
          }
          Files.createDirectories(target.getParent());
          Files.writeString(target, json, StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING);
          stats.sdWritten++;
        }
      }
    }
    return stats;
  }

  private LocalStats processLocalProfiles(SnapshotSupport.SnapshotEngine snapshotEngine,
      Path effectiveOutDir) throws IOException {
    LocalStats stats = new LocalStats();
    if (profilesDir == null) {
      return stats;
    }
    if (!Files.exists(profilesDir) || !Files.isDirectory(profilesDir)) {
      System.err.printf(Locale.ROOT, "Profiles directory not found or not a directory: %s%n",
          profilesDir);
      return stats;
    }

    Path localOutBase = effectiveOutDir.resolve("local");

    try (var stream = Files.find(profilesDir, Integer.MAX_VALUE,
        (path, attrs) -> !attrs.isDirectory()
            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))) {
      for (Path f : (Iterable<Path>) stream::iterator) {
        try {
          String input = Files.readString(f);
          snapshotEngine.cacheResource(input);
        } catch (Exception e) {
          System.err.printf(Locale.ROOT, "Skip (load into support) failed: %s (%s)%n", f,
              e.getMessage());
        }
      }
    }

    try (var stream = Files.find(profilesDir, Integer.MAX_VALUE,
        (path, attrs) -> !attrs.isDirectory()
            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))) {
      for (Path f : (Iterable<Path>) stream::iterator) {
        String json;
        try {
          json = Files.readString(f);
        } catch (Exception e) {
          System.err.printf(Locale.ROOT, "Skip unreadable file: %s (%s)%n", f, e.getMessage());
          continue;
        }

        JsonFieldExtractor.ProfileFields profileFields = JsonFieldExtractor.extractProfileFields(
            json);
        if (!"StructureDefinition".equals(profileFields.resourceType())) {
          continue;
        }
        stats.total++;

        boolean hasSnapshot = SNAPSHOT_FIELD.matcher(json).find();
        boolean didGenerate = forceSnapshot || !hasSnapshot;
        if (didGenerate) {
          try {
            json = snapshotEngine.generateSnapshot(json, pretty,
                profileFields.url(), profileFields.name());
            stats.generated++;
            if (debug) {
              System.out.printf(Locale.ROOT, "Generated local snapshot: %s%n", f);
            }
          } catch (Exception e) {
            System.err.printf(Locale.ROOT, "Snapshot generation failed for %s: %s%n", f,
                e.getMessage());
            continue;
          }
        }

        Path rel = profilesDir.relativize(f);
        Path target = localOutBase.resolve(rel);
        try {
          if (!didGenerate && !overwrite && Files.exists(target)) {
            continue;
          }
          Files.createDirectories(target.getParent());
          Files.writeString(target, json, StandardOpenOption.CREATE,
              StandardOpenOption.TRUNCATE_EXISTING);
          stats.written++;
        } catch (Exception e) {
          System.err.printf(Locale.ROOT, "Write failed for %s: %s%n", target, e.getMessage());
        }
      }
    }
    return stats;
  }

  // Compatibility wrappers for tests and existing callers.
  static List<String> parseSushiDepsYaml(String yamlText) {
    return DependencyInputParser.parseSushiDepsYaml(yamlText);
  }

  static List<String> parsePackageJsonDependencies(String jsonText) {
    return DependencyInputParser.parsePackageJsonDependencies(jsonText);
  }

  static Optional<String> parseSushiFhirVersion(String yamlText) {
    return DependencyInputParser.parseSushiFhirVersion(yamlText);
  }

  static List<String> selectLatestCoordinatesByPackageId(Collection<String> coordinates) {
    return CoordinateSelector.selectLatestCoordinatesByPackageId(coordinates);
  }

  static Optional<String> parseCacheVersionFromIni(String iniText) {
    return CacheSafety.parseCacheVersionFromIni(iniText);
  }

  static boolean isSupportedCacheVersion(String version) {
    return CacheSafety.isSupportedCacheVersion(version);
  }

  static boolean shouldValidateDefaultCacheSafety(Path effectiveCacheDir) {
    return CacheSafety.shouldValidateDefaultCacheSafety(effectiveCacheDir, defaultCacheDir());
  }

  static String summarizeException(Throwable ex) {
    return ExceptionSummary.summarizeException(ex);
  }

  private static final class PackageStats {
    int total;
    int generated;
    int sdWritten;
    int filesCopied;
  }

  private static final class LocalStats {
    int total;
    int generated;
    int written;
  }
}
