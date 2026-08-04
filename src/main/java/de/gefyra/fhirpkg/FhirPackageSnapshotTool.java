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

  @Option(names = {"--package-file", "--file"},
      description = "Local FHIR package tarball(s) (*.tgz) to install into the cache (repeatable; id/version are read from the package)")
  List<Path> packageFiles = new ArrayList<>();

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

  @Option(names = {
      "--no-auto-core"}, description = "Do NOT automatically load the FHIR core package as snapshot context when it is missing")
  boolean noAutoCore = false;

  @Option(names = {
      "--ignore-snapshot-errors"}, description = "Exit with 0 even when snapshots could not be generated (default: exit code 6)")
  boolean ignoreSnapshotErrors = false;

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

    List<Path> localPackageFiles = packageFiles == null ? List.of() : packageFiles;
    printLocalPackageFiles(localPackageFiles);

    Optional<String> sushiFhirVersion = DependencyInputParser.gatherFhirVersionFromSushi(
        sushiDepsFile, sushiDepsStr);

    if (resolvedRequested.isEmpty() && localPackageFiles.isEmpty() && profilesDir == null) {
      if (sushiDepsFile != null || sushiDepsStr != null || packageJsonFile != null) {
        System.out.println(
            "No dependencies found in provided dependency sources, skipping package installation.");
        return 0;
      }
      System.err.println(
          "No packages specified. Use -p, --package-file, --sushi-deps-*, or --package-json-file (or provide --profiles-dir). Aborting.");
      return 2;
    }

    Files.createDirectories(effectiveCacheDir);
    IPackageCacheManager cache = buildCache(effectiveCacheDir);
    Set<Path> knownCacheDirs = PackageLoadingSupport.initKnownCacheDirs(effectiveCacheDir);

    List<NpmPackage> allPkgs = loadRequestedAndDependencyPackages(cache, resolvedRequested,
        localPackageFiles, knownCacheDirs);
    if (allPkgs.isEmpty()) {
      if (profilesDir == null) {
        System.err.println("No packages loaded – aborting.");
        return 3;
      }
      System.out.println("No FHIR packages loaded; continuing with local profiles only.");
    }

    String selectedFhirVersion = selectFhirVersion(sushiFhirVersion, allPkgs);
    SnapshotSupport.FhirRelease release = SnapshotSupport.resolveFhirRelease(selectedFhirVersion);
    List<NpmPackage> contextPkgs = ensureCorePackageInContext(cache, allPkgs, release,
        knownCacheDirs);
    SnapshotSupport.SnapshotEngine snapshotEngine = SnapshotSupport.createSnapshotEngine(release,
        selectedFhirVersion, contextPkgs);

    PackageStats packageStats = new PackageStats();
    if (profilesDir == null) {
      packageStats = processPackageOutputs(allPkgs, effectiveOutDir, snapshotEngine);
    }

    LocalStats localStats = processLocalProfiles(snapshotEngine, effectiveOutDir);

    System.out.printf(Locale.ROOT,
        "Done: %d SDs found, %d snapshots generated, %d failed, %d SD files written, %d files copied. Local: %d SDs, %d generated, %d failed, %d written.%n",
        packageStats.total, packageStats.generated, packageStats.failed, packageStats.sdWritten,
        packageStats.filesCopied,
        localStats.total, localStats.generated, localStats.failed, localStats.written);
    System.out.printf(Locale.ROOT, "Output directory: %s%n", effectiveOutDir);
    System.out.printf(Locale.ROOT, "Cache directory: %s%n", effectiveCacheDir);

    int snapshotFailures = packageStats.failed + localStats.failed;
    if (snapshotFailures > 0) {
      if (ignoreSnapshotErrors) {
        System.err.printf(Locale.ROOT,
            "Warning: %d snapshot(s) could not be generated; ignored due to --ignore-snapshot-errors.%n",
            snapshotFailures);
        return 0;
      }
      System.err.printf(Locale.ROOT,
          "Error: %d snapshot(s) could not be generated (see messages above). Use --ignore-snapshot-errors to exit 0 anyway.%n",
          snapshotFailures);
      return 6;
    }
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

  private void printLocalPackageFiles(List<Path> localPackageFiles) {
    if (localPackageFiles.isEmpty()) {
      return;
    }
    System.out.println("Local package files:");
    for (Path file : localPackageFiles) {
      System.out.println("  - " + file.toAbsolutePath().normalize());
    }
  }

  private IPackageCacheManager buildCache(Path effectiveCacheDir) throws Exception {
    FilesystemPackageCacheManager.Builder cacheBuilder = new FilesystemPackageCacheManager.Builder()
        .withCacheFolder(effectiveCacheDir.toString())
        .withPackageServers(List.of(new PackageServer(registryUrl)));
    return cacheBuilder.build();
  }

  List<NpmPackage> loadRequestedAndDependencyPackages(IPackageCacheManager cache,
      List<String> resolvedRequested, Set<Path> knownCacheDirs) {
    return loadRequestedAndDependencyPackages(cache, resolvedRequested, List.of(), knownCacheDirs);
  }

  List<NpmPackage> loadRequestedAndDependencyPackages(IPackageCacheManager cache,
      List<String> resolvedRequested, List<Path> localPackageFiles, Set<Path> knownCacheDirs) {
    List<NpmPackage> allPkgs = new ArrayList<>();
    Set<String> seenByName = new HashSet<>();

    // Local tarballs win over registry coordinates with the same package id.
    for (Path file : localPackageFiles) {
      try {
        NpmPackage p = PackageLoadingSupport.installPackageFromFile(cache, file, knownCacheDirs);
        System.out.printf(Locale.ROOT, "Installed local package file %s as %s#%s%n", file, p.name(),
            p.version());
        if (seenByName.add(p.name())) {
          allPkgs.add(p);
        }
      } catch (Exception e) {
        System.err.printf(Locale.ROOT, "Failed to install package file %s (%s). Continuing.%n",
            file, summarizeException(e));
      }
    }

    for (String coord : resolvedRequested) {
      if (KnownProblematicPackages.isKnownProblematicCoordinate(coord)) {
        KnownProblematicPackages.logSkippingKnownProblematicPackage("requested packages", coord);
        continue;
      }
      try {
        NpmPackage p = PackageLoadingSupport.loadPackage(cache, coord, knownCacheDirs);
        if (seenByName.add(p.name())) {
          allPkgs.add(p);
        }
      } catch (Exception e) {
        System.err.printf(Locale.ROOT, "Failed to install requested package %s (%s). Continuing.%n",
            coord, summarizeException(e));
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

  /**
   * The snapshot context is built exclusively from the loaded packages, so without a core package
   * every profile that derives from a base resource fails. Returns {@code allPkgs} plus the matching
   * core package, which is used as context only and is not written to the output directory.
   */
  List<NpmPackage> ensureCorePackageInContext(IPackageCacheManager cache, List<NpmPackage> allPkgs,
      SnapshotSupport.FhirRelease release, Set<Path> knownCacheDirs) {
    SnapshotSupport.CoreCoordinate core = SnapshotSupport.coreCoordinate(release);
    for (NpmPackage p : allPkgs) {
      if (core.id().equals(p.name())) {
        return allPkgs;
      }
    }

    for (NpmPackage p : allPkgs) {
      if (SnapshotSupport.isCorePackage(p.name())) {
        System.err.printf(Locale.ROOT,
            "Warning: loaded core package %s#%s does not match the %s snapshot context (expected %s).%n",
            p.name(), p.version(), release, core.id());
      }
    }

    if (noAutoCore) {
      System.out.printf(Locale.ROOT,
          "No %s among the loaded packages; skipping auto-load due to --no-auto-core.%n", core.id());
      return allPkgs;
    }

    System.out.printf(Locale.ROOT,
        "No %s among the loaded packages; loading %s as snapshot context.%n", core.id(),
        core.asCoordinate());
    try {
      NpmPackage corePkg = PackageLoadingSupport.loadPackage(cache, core.asCoordinate(),
          knownCacheDirs);
      List<NpmPackage> withCore = new ArrayList<>(allPkgs);
      withCore.add(corePkg);
      return withCore;
    } catch (Exception e) {
      System.err.printf(Locale.ROOT,
          "Failed to load %s as snapshot context (%s). Snapshots may fail.%n", core.asCoordinate(),
          summarizeException(e));
      return allPkgs;
    }
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
            try {
              json = snapshotEngine.generateSnapshot(json, pretty,
                  profileFields.url(), profileFields.name());
              stats.generated++;
              if (debug) {
                System.out.printf(Locale.ROOT, "Generated snapshot: %s#%s/%s%n", p.name(),
                    p.version(), resName);
              }
            } catch (Exception e) {
              // Keep going so a single unbuildable profile does not hide the state of all others.
              stats.failed++;
              didGenerate = false;
              System.err.printf(Locale.ROOT,
                  "Snapshot generation failed for %s#%s/%s (%s). Keeping the resource unchanged.%n",
                  p.name(), p.version(), resName, summarizeException(e));
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
            stats.failed++;
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
    int failed;
    int sdWritten;
    int filesCopied;
  }

  private static final class LocalStats {
    int total;
    int generated;
    int failed;
    int written;
  }
}
