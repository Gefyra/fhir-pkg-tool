package de.gefyra.fhirpkg;

import ca.uhn.fhir.context.FhirContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport;
import ca.uhn.fhir.context.support.IValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.NpmPackageValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import ca.uhn.fhir.context.support.ValidationSupportContext;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.FilesystemPackageCacheManager;
import org.hl7.fhir.utilities.npm.IPackageCacheManager;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.hl7.fhir.utilities.npm.PackageServer;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

/**
 * CLI tool that downloads FHIR NPM packages, resolves dependencies, generates StructureDefinition snapshots,
 * and writes them as JSON files.
 */
@Command(
    name = "fhir-pkg-tool",
    mixinStandardHelpOptions = true,
    version = "1.0-SNAPSHOT",
    description = "Downloads FHIR NPM packages, resolves dependencies, generates StructureDefinition snapshots, and writes them as JSON files."
)
public class FhirPackageSnapshotTool implements Callable<Integer> {
    private static final String KNOWN_PROBLEMATIC_PACKAGE_NAME = "hl7.fhir.extensions.r5";
    private static final String KNOWN_PROBLEMATIC_PACKAGE_VERSION = "4.0.1";
    private static final String EXPECTED_CACHE_METADATA_VERSION = "4";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Option(names = {"-p", "--package"},
            description = "FHIR NPM packages (repeatable or comma-separated; e.g. hl7.fhir.r4.core@4.0.1,hl7.fhir.us.core@6.1.0)")
    List<String> pkgCoordinates = new ArrayList<>();

    @Option(names = {"--sushi-deps-str"}, description = "YAML block (as string) from sushi-config.yaml with 'dependencies:'")
    String sushiDepsStr;

    @Option(names = {"--sushi-deps-file"}, description = "Path to sushi-config.yaml (or a file containing the YAML dependencies)")
    Path sushiDepsFile;

    @Option(names = {"--package-json-file"}, description = "Path to package.json (only 'dependencies' are read; 'devDependencies' are ignored)")
    Path packageJsonFile;

    public static Path defaultOutputDir() {
        return defaultCacheDir();
    }

    @Option(names = {"-o", "--out"}, description = "Output directory for StructureDefinitions (default: ~/.fhir/packages; Windows: C:\\Users\\<USER>\\.fhir\\packages)")
    Path outDir = defaultOutputDir();

    public static Path defaultCacheDir() {
        // Check if we're in GitHub Actions
        String githubActions = System.getenv("GITHUB_ACTIONS");
        if ("true".equals(githubActions)) {
            // In GitHub Actions, use HOME env var instead of user.home property
            String home = System.getenv("HOME");
            if (home != null && !home.isBlank()) {
                return Paths.get(home, ".fhir", "packages");
            }
        }
        
        return Paths.get(System.getProperty("user.home"), ".fhir", "packages");
    }

    @Option(names = {"--registry"}, description = "Package registry (default: https://packages.fhir.org)")
    String registryUrl = "https://packages.fhir.org";

    @Option(names = {"--skip-deps"}, description = "Do NOT automatically load dependencies")
    boolean skipDependencies = false;

    @Option(names = {"--overwrite"}, description = "Overwrite existing files")
    boolean overwrite = false;

    @Option(names = {"--pretty"}, description = "Pretty-print JSON")
    boolean pretty = true;

    @Option(names = {"--force-snapshot"}, description = "Always (re)generate snapshots, even if present")
    boolean forceSnapshot = false;

    @Option(names = {"--profiles-dir"}, description = "Directory with local StructureDefinition JSONs (processed recursively)")
    Path profilesDir;

    @Option(names = {"--repair-lock-files"}, description = "Delete '*.lock' files in default cache directory before package loading")
    boolean repairLockFiles = false;

    @Option(names = {"--debug"}, description = "Print stack traces for execution errors")
    boolean debug = false;

    public static void main(String[] args) {
        System.out.println("FHIR Package Tool starting...");
        if (!hasDebugFlag(args)) {
            // Keep third-party logging quiet by default; detailed logs are available with --debug.
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
        Path normalizedOutDir = outDir.toAbsolutePath().normalize();
        Path effectiveOutDir = normalizedOutDir;
        Path effectiveCacheDir = effectiveOutDir;
        boolean validateDefaultCacheSafety = shouldValidateDefaultCacheSafety(effectiveCacheDir);

        // Debug output: Show which directories will be used
        System.out.println("FHIR Package Tool Configuration:");
        System.out.println("  Configured output directory: " + normalizedOutDir);
        System.out.println("  Effective output directory: " + effectiveOutDir);
        System.out.println("  Effective cache directory: " + effectiveCacheDir);
        if (validateDefaultCacheSafety) {
            System.out.println("  Cache safety checks: enabled (default cache path)");
        } else {
            System.out.println("  Cache safety checks: skipped (non-default output path)");
            if (repairLockFiles) {
                System.out.println("  Note: --repair-lock-files is ignored for non-default output paths");
            }
        }
        
        // Show how directories were determined
        String githubActions = System.getenv("GITHUB_ACTIONS");
        if ("true".equals(githubActions)) {
            System.out.println("  GitHub Actions detected (GITHUB_ACTIONS=true)");
            System.out.println("  HOME environment variable: " + System.getenv("HOME"));
            System.out.println("  user.home system property: " + System.getProperty("user.home"));
        } else {
            System.out.println("  Running in local environment");
        }
        System.out.println();

        if (validateDefaultCacheSafety) {
            Optional<String> cacheVersion;
            try {
                cacheVersion = readCacheVersionFromCacheIni(effectiveCacheDir);
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
                            detectedVersion, EXPECTED_CACHE_METADATA_VERSION);
                    return 4;
                }
                System.out.println("Detected cache metadata version in packages.ini: " + detectedVersion);
            } else if (Files.exists(effectiveCacheDir.resolve("packages.ini"))) {
                System.err.println("Error: packages.ini found but [cache]/version could not be parsed. Aborting to avoid unsafe cache operations.");
                return 4;
            }
            List<Path> lockFiles;
            try {
                lockFiles = findLockFiles(effectiveCacheDir);
            } catch (IOException e) {
                System.err.printf(Locale.ROOT, "Error: Failed to list lock files in %s (%s). Aborting.%n",
                        effectiveCacheDir, e.getMessage());
                return 5;
            }
            if (!lockFiles.isEmpty()) {
                System.err.printf(Locale.ROOT, "Error: Found %d .lock file(s) in cache.%n", lockFiles.size());
                if (repairLockFiles) {
                    int deleted;
                    try {
                        deleted = deleteLockFiles(lockFiles);
                    } catch (IOException e) {
                        System.err.printf(Locale.ROOT, "Error: Failed to delete lock files (%s). Aborting.%n", e.getMessage());
                        return 5;
                    }
                    System.out.printf(Locale.ROOT, "Repair: deleted %d .lock file(s).%n", deleted);
                    List<Path> remainingLocks;
                    try {
                        remainingLocks = findLockFiles(effectiveCacheDir);
                    } catch (IOException e) {
                        System.err.printf(Locale.ROOT, "Error: Failed to re-check lock files in %s (%s). Aborting.%n",
                                effectiveCacheDir, e.getMessage());
                        return 5;
                    }
                    if (!remainingLocks.isEmpty()) {
                        System.err.printf(Locale.ROOT, "Error: %d .lock file(s) remain after repair. Aborting.%n", remainingLocks.size());
                        return 5;
                    }
                } else {
                    System.err.println("Hint: rerun with --repair-lock-files to remove stale lock files.");
                    return 5;
                }
            }
        }
        
        Files.createDirectories(effectiveOutDir);

        // 1) Collect package coordinates: -p (comma-separated is fine), Sushi file/string, package.json file
        Set<String> requested = new LinkedHashSet<>();
        if (pkgCoordinates != null) {
            for (String s : pkgCoordinates) {
                if (s == null) continue;
                Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(x -> !x.isBlank())
                        .forEach(requested::add);
            }
        }
        List<String> sushiPackages = gatherPkgCoordsFromSushi(sushiDepsFile, sushiDepsStr);
        requested.addAll(sushiPackages);
        List<String> packageJsonPackages = gatherPkgCoordsFromPackageJson(packageJsonFile);
        requested.addAll(packageJsonPackages);
        List<String> resolvedRequested = selectLatestCoordinatesByPackageId(requested);
        
        // Debug: Show all requested packages
        if (!resolvedRequested.isEmpty()) {
            System.out.println("Requested packages:");
            for (String pkg : resolvedRequested) {
                System.out.println("  - " + pkg);
            }
        }

        Optional<String> sushiFhirVersion = gatherFhirVersionFromSushi(sushiDepsFile, sushiDepsStr);

        // If dependency source files/strings are provided but no dependencies found, skip package installation
        if (resolvedRequested.isEmpty() && profilesDir == null) {
            if (sushiDepsFile != null || sushiDepsStr != null || packageJsonFile != null) {
                System.out.println("No dependencies found in provided dependency sources, skipping package installation.");
                return 0;
            }
            System.err.println("No packages specified. Use -p, --sushi-deps-*, or --package-json-file (or provide --profiles-dir). Aborting.");
            return 2;
        }

        // 2) Configure package cache/registry
        // FilesystemPackageCacheManager in utilities 6.6.6 uses a Builder API
        Files.createDirectories(effectiveCacheDir);
        Set<Path> knownCacheDirs = initKnownCacheDirs(effectiveCacheDir);
        FilesystemPackageCacheManager.Builder cacheBuilder = new FilesystemPackageCacheManager.Builder()
                .withCacheFolder(effectiveCacheDir.toString())
                .withPackageServers(List.of(new PackageServer(registryUrl)));
        IPackageCacheManager cache = cacheBuilder.build();

        // 3) Load root packages (avoid duplicates by name)
        List<NpmPackage> allPkgs = new ArrayList<>();
        Set<String> seenByName = new HashSet<>();
        for (String coord : resolvedRequested) {
            if (isKnownProblematicCoordinate(coord)) {
                logSkippingKnownProblematicPackage("requested packages", coord);
                continue;
            }
            
            NpmPackage p = loadPackage(cache, coord, knownCacheDirs);
            if (seenByName.add(p.name())) {
                allPkgs.add(p);
            }
        }

        // 4) Resolve dependencies transitively (unless --skip-deps)
        if (!skipDependencies) {
            for (int i = 0; i < allPkgs.size(); i++) {
                NpmPackage root = allPkgs.get(i);
                List<NpmPackage> deps = loadAllDependencies(cache, root, seenByName, knownCacheDirs);
                allPkgs.addAll(deps);
            }
        }

        if (allPkgs.isEmpty()) {
            if (profilesDir == null) {
                System.err.println("No packages loaded – aborting.");
                return 3;
            }
            System.out.println("No FHIR packages loaded; continuing with local profiles only.");
        }

        // 5) Choose FHIR context: prefer sushi-config value when provided, otherwise fallback to packages/default
        FhirContext ctx;
        if (sushiFhirVersion.isPresent()) {
            ctx = pickFhirContext(sushiFhirVersion.get());
        } else if (!allPkgs.isEmpty()) {
            ctx = pickFhirContext(allPkgs.get(0));
        } else {
            System.err.println("No FHIR version available; defaulting to R5 context.");
            ctx = pickFhirContext((String) null);
        }

        // 6) Build ValidationSupport chain
        IValidationSupport chain = buildValidationChain(ctx, allPkgs);

        // 7) If no --profiles-dir: copy packages and snapshot their StructureDefinitions
        //    If --profiles-dir is present: skip package output entirely (local-only output)
        int generated = 0, total = 0, sdWritten = 0, filesCopied = 0;
        
        if (profilesDir == null) {
            for (NpmPackage p : allPkgs) {
                String pkgFolderName = p.name() + "#" + p.version();
                Path pkgOutDir = effectiveOutDir.resolve(pkgFolderName);
                
                // 7a) Copy all folders/files
                var folders = p.getFolders();
                for (Map.Entry<String, org.hl7.fhir.utilities.npm.NpmPackage.NpmPackageFolder> e : folders.entrySet()) {
                    String folderName = e.getKey();
                    var folder = e.getValue();
                    Path folderOut = pkgOutDir.resolve(folderName);
                    Files.createDirectories(folderOut);
                    for (String fname : folder.listFiles()) {
                        Path target = folderOut.resolve(fname);
                        if (!overwrite && Files.exists(target)) {
                            continue;
                        }
                        try (InputStream is = p.load(folderName, fname)) {
                            if (is == null) continue;
                            byte[] bytes = is.readAllBytes();
                            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            filesCopied++;
                        }
                    }
                }

                // 7b) Snapshot StructureDefinitions and overwrite copied files when a new snapshot was generated
                for (String resName : p.listResources("StructureDefinition")) {
                    total++;
                    try (InputStream is = p.load("package", resName)) {
                        if (is == null) continue;
                        String json = new String(is.readAllBytes());

                        boolean hasSnapshot = SNAPSHOT_FIELD.matcher(json).find();
                        boolean didGenerate = forceSnapshot || !hasSnapshot;
                        if (didGenerate) {
                            SnapshotGeneratingValidationSupport snap = new SnapshotGeneratingValidationSupport(ctx);
                            ValidationSupportContext vsc = new ValidationSupportContext(chain);
                            IBaseResource parsed = ctx.newJsonParser().parseResource(json);
                            IBaseResource withSnap = snap.generateSnapshot(vsc, parsed, getUrlFromJson(json), null, getNameFromJson(json));
                            json = pretty
                                    ? ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(withSnap)
                                    : ctx.newJsonParser().encodeResourceToString(withSnap);
                            generated++;
                        }

                        Path target = pkgOutDir.resolve("package").resolve(resName);
                        // Write if we generated a new snapshot, regardless of existing file; otherwise honor overwrite flag
                        if (!didGenerate && !overwrite && Files.exists(target)) {
                            continue;
                        }
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                        sdWritten++;
                    }
                }
            }
        }

        // 8) Optionally: process local profiles from --profiles-dir
        int localTotal = 0, localGenerated = 0, localWritten = 0;
        if (profilesDir != null) {
            if (!Files.exists(profilesDir) || !Files.isDirectory(profilesDir)) {
                System.err.printf(Locale.ROOT, "Profiles directory not found or not a directory: %s%n", profilesDir);
            } else {
                Path localOutBase = effectiveOutDir.resolve("local");
                SnapshotGeneratingValidationSupport snap = new SnapshotGeneratingValidationSupport(ctx);

                // Build a local support layer with all local resources so cross-references resolve (bases, extensions)
                NpmPackageValidationSupport localSupport = new NpmPackageValidationSupport(ctx);
                var parser = ctx.newJsonParser();
                parser.setParserErrorHandler(new ca.uhn.fhir.parser.LenientErrorHandler(false));

                // First pass: load all JSON resources from the folder into localSupport
                try (var stream = Files.find(profilesDir, Integer.MAX_VALUE,
                        (path, attrs) -> !attrs.isDirectory()
                                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        try {
                            String input = Files.readString(f);
                            // parse and add any FHIR resource present; failures are non-fatal
                            IBaseResource res = parser.parseResource(input);
                            if (res != null) {
                                localSupport.addResource(res);
                            }
                        } catch (Exception e) {
                            System.err.printf(Locale.ROOT, "Skip (load into support) failed: %s (%s)%n", f, e.getMessage());
                        }
                    }
                }

                // Use a chain that prefers local resources first, then the package chain
                IValidationSupport localChain = new ValidationSupportChain(localSupport, chain);
                ValidationSupportContext vsc = new ValidationSupportContext(localChain);

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

                        // Only handle StructureDefinition resources
                        String rt = extractJsonString(json, "resourceType");
                        if (rt == null || !"StructureDefinition".equals(rt)) continue;
                        localTotal++;

                        boolean hasSnapshot = SNAPSHOT_FIELD.matcher(json).find();
                        boolean didGenerate = forceSnapshot || !hasSnapshot;
                        if (didGenerate) {
                            try {
                                IBaseResource parsedRes = parser.parseResource(json);
                                IBaseResource withSnap = snap.generateSnapshot(vsc, parsedRes, getUrlFromJson(json), null, getNameFromJson(json));
                                json = pretty
                                        ? ctx.newJsonParser().setPrettyPrint(true).encodeResourceToString(withSnap)
                                        : ctx.newJsonParser().encodeResourceToString(withSnap);
                                localGenerated++;
                            } catch (Exception e) {
                                System.err.printf(Locale.ROOT, "Snapshot generation failed for %s: %s%n", f, e.getMessage());
                                continue;
                            }
                        }

                        // mirror relative path under out/local
                        Path rel = profilesDir.relativize(f);
                        Path target = localOutBase.resolve(rel);
                        try {
                            if (!didGenerate && !overwrite && Files.exists(target)) {
                                continue;
                            }
                            Files.createDirectories(target.getParent());
                            Files.writeString(target, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                            localWritten++;
                        } catch (Exception e) {
                            System.err.printf(Locale.ROOT, "Write failed for %s: %s%n", target, e.getMessage());
                        }
                    }
                }
            }
        }

        System.out.printf(Locale.ROOT,
                "Done: %d SDs found, %d snapshots generated, %d SD files written, %d files copied. Local: %d SDs, %d generated, %d written.%n",
                total, generated, sdWritten, filesCopied, localTotal, localGenerated, localWritten);
        System.out.printf(Locale.ROOT,
                "Output directory: %s%n", effectiveOutDir);
        System.out.printf(Locale.ROOT,
                "Cache directory: %s%n", effectiveCacheDir);

        return 0;
    }

    // Look for a top-level field named "snapshot"; avoid '{' to sidestep JDK21 preview parsing issues
    private static final Pattern SNAPSHOT_FIELD = Pattern.compile("\\\"snapshot\\\"\\s*:", Pattern.DOTALL);

    private static NpmPackage loadPackage(IPackageCacheManager cache,
                                          String coordinate,
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

    private static List<NpmPackage> loadAllDependencies(IPackageCacheManager cache,
                                                        NpmPackage root,
                                                        Set<String> seenByName,
                                                        Set<Path> knownCacheDirs) throws IOException {
        List<String> deps = root.dependencies(); // e.g., entries like "package.id#1.2.3"
        if (deps == null || deps.isEmpty()) return List.of();

        List<NpmPackage> out = new ArrayList<>();
        for (String entry : deps) {
            String name = entry;
            String version = null;
            int hashIdx = entry.indexOf('#');
            if (hashIdx >= 0) {
                name = entry.substring(0, hashIdx);
                version = entry.substring(hashIdx + 1);
            }

            if (isKnownProblematicPackage(name, version)) {
                logSkippingKnownProblematicPackage("resolved dependencies", name, version);
                continue;
            }

            if (!seenByName.add(name)) continue; // already loaded

            NpmPackage p = (version == null || version.isBlank())
                    ? cache.loadPackage(name)
                    : cache.loadPackage(name, version);
            notePackageCacheLocation(p, knownCacheDirs);
            out.add(p);
            out.addAll(loadAllDependencies(cache, p, seenByName, knownCacheDirs));
        }
        return out;
    }

    private static FhirContext pickFhirContext(NpmPackage pkg) {
        return pickFhirContext(pkg.fhirVersion());
    }

    private static FhirContext pickFhirContext(String version) {
        String fhirVer = Optional.ofNullable(version).orElse("").toLowerCase(Locale.ROOT);
        if (fhirVer.startsWith("5")) {
            return FhirContext.forR5Cached();
        } else if (fhirVer.startsWith("4.3")) {
            return FhirContext.forR4BCached();
        } else if (fhirVer.startsWith("4")) {
            return FhirContext.forR4Cached();
        } else if (fhirVer.startsWith("3")) {
            return FhirContext.forDstu3Cached();
        }
        return FhirContext.forR5Cached();
    }

    private static IValidationSupport buildValidationChain(FhirContext ctx, List<NpmPackage> pkgs) {
        DefaultProfileValidationSupport defaultSupport = new DefaultProfileValidationSupport(ctx);
        NpmPackageValidationSupport npmSupport = new NpmPackageValidationSupport(ctx);
        // Load resources and binaries from each NpmPackage into the npmSupport
        for (NpmPackage p : pkgs) {
            try {
                // For snapshot generation, StructureDefinitions are sufficient
                for (String type : List.of("StructureDefinition")) {
                    for (String resName : p.listResources(type)) {
                        try (InputStream is = p.load("package", resName)) {
                            if (is == null) continue;
                            String input = new String(is.readAllBytes());
                            var parser = ctx.newJsonParser();
                            parser.setParserErrorHandler(new ca.uhn.fhir.parser.LenientErrorHandler(false));
                            IBaseResource resource = parser.parseResource(input);
                            npmSupport.addResource(resource);
                        }
                    }
                }
                for (String binaryName : p.list("other")) {
                    try (InputStream bin = p.load("other", binaryName)) {
                        byte[] bytes = bin.readAllBytes();
                        npmSupport.addBinary(bytes, binaryName);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed loading package resources: " + p.name() + "#" + p.version(), e);
            }
        }
        InMemoryTerminologyServerValidationSupport inMemTerm = new InMemoryTerminologyServerValidationSupport(ctx);
        CommonCodeSystemsTerminologyService commonTerminology = new CommonCodeSystemsTerminologyService(ctx);
        PrePopulatedValidationSupport prepop = new PrePopulatedValidationSupport(ctx);
        return new ValidationSupportChain(
                defaultSupport,
                npmSupport,
                prepop,
                commonTerminology,
                inMemTerm
        );
    }

    private static Set<Path> initKnownCacheDirs(Path cacheDir) {
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
            System.err.printf(Locale.ROOT, "Failed to scan cache directory %s: %s%n", cacheDir, e.getMessage());
        }
        return known;
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
                        "Cached package %s#%s at %s%n",
                        Optional.ofNullable(pkg.name()).orElse(""),
                        Optional.ofNullable(pkg.version()).orElse(""),
                        resolved);
            }
        } catch (Exception e) {
            System.err.printf(Locale.ROOT,
                    "Cached package %s#%s but resolved cache path failed: %s%n",
                    Optional.ofNullable(pkg.name()).orElse(""),
                    Optional.ofNullable(pkg.version()).orElse(""),
                    e.getMessage());
        }
    }

    // --- Dependency source parsing ---
    static List<String> parseSushiDepsYaml(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) return List.of();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root = yaml.load(yamlText);
        if (!(root instanceof Map<?, ?> map)) return List.of();

        // Only parse the "dependencies" section, not the entire root
        if (!map.containsKey("dependencies")) return List.of();
        Object depsNode = map.get("dependencies");
        if (!(depsNode instanceof Map<?, ?> deps)) return List.of();
        return parseDependencyMap(deps, "sushi-config");
    }

    private static List<String> gatherPkgCoordsFromSushi(Path file, String inlineYaml) throws IOException {
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

    static List<String> parsePackageJsonDependencies(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) return List.of();
        try {
            Map<String, Object> root = JSON_MAPPER.readValue(jsonText, new TypeReference<>() {});
            Object depsNode = root.get("dependencies");
            if (!(depsNode instanceof Map<?, ?> deps)) return List.of();
            return parseDependencyMap(deps, "package.json");
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid package.json content", e);
        }
    }

    private static List<String> gatherPkgCoordsFromPackageJson(Path packageJsonPath) throws IOException {
        if (packageJsonPath == null) {
            return List.of();
        }
        return parsePackageJsonDependencies(Files.readString(packageJsonPath));
    }

    private static List<String> parseDependencyMap(Map<?, ?> deps, String sourceName) {
        List<String> coords = new ArrayList<>();
        for (Map.Entry<?, ?> e : deps.entrySet()) {
            String pkgName = String.valueOf(e.getKey()).trim();
            if (pkgName.isBlank()) {
                continue;
            }

            String version = extractDependencyVersion(e.getValue());

            if (isKnownProblematicPackage(pkgName, version)) {
                logSkippingKnownProblematicPackage(sourceName, pkgName, version);
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

    static Optional<String> parseCacheVersionFromIni(String iniText) {
        if (iniText == null || iniText.isBlank()) {
            return Optional.empty();
        }
        String section = "";
        String[] lines = iniText.split("\\R");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim();
                continue;
            }
            int eqIdx = line.indexOf('=');
            if (eqIdx < 0) {
                continue;
            }
            String key = line.substring(0, eqIdx).trim();
            String value = line.substring(eqIdx + 1).trim();
            if ("cache".equalsIgnoreCase(section) && "version".equalsIgnoreCase(key) && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    static boolean isSupportedCacheVersion(String version) {
        return EXPECTED_CACHE_METADATA_VERSION.equals(Optional.ofNullable(version).orElse("").trim());
    }

    static boolean shouldValidateDefaultCacheSafety(Path effectiveCacheDir) {
        if (effectiveCacheDir == null) {
            return false;
        }
        Path normalizedDefault = defaultCacheDir().toAbsolutePath().normalize();
        return normalizedDefault.equals(effectiveCacheDir.toAbsolutePath().normalize());
    }

    private static Optional<String> readCacheVersionFromCacheIni(Path cacheDir) throws IOException {
        Path iniPath = cacheDir.resolve("packages.ini");
        if (!Files.exists(iniPath)) {
            return Optional.empty();
        }
        return parseCacheVersionFromIni(Files.readString(iniPath));
    }

    private static List<Path> findLockFiles(Path cacheDir) throws IOException {
        List<Path> lockFiles = new ArrayList<>();
        if (!Files.exists(cacheDir) || !Files.isDirectory(cacheDir)) {
            return lockFiles;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, "*.lock")) {
            for (Path lock : stream) {
                lockFiles.add(lock);
            }
        }
        return lockFiles;
    }

    private static int deleteLockFiles(List<Path> lockFiles) throws IOException {
        int deleted = 0;
        for (Path lock : lockFiles) {
            try {
                if (Files.deleteIfExists(lock)) {
                    deleted++;
                }
            } catch (IOException e) {
                throw new IOException("Failed to delete lock file " + lock + " (" + e.getMessage() + ")", e);
            }
        }
        return deleted;
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

    static String summarizeException(Throwable ex) {
        if (ex == null) {
            return "Unknown error";
        }
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getMessage();
        }
        if (msg == null || msg.isBlank()) {
            return root.getClass().getSimpleName();
        }
        return msg;
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

    private static boolean isKnownProblematicCoordinate(String coordinate) {
        ParsedCoordinate parsed = parseCoordinate(coordinate);
        if (parsed == null) {
            return false;
        }
        return isKnownProblematicPackage(parsed.name(), parsed.version());
    }

    private static boolean isKnownProblematicPackage(String name, String version) {
        if (name == null || version == null) {
            return false;
        }
        return KNOWN_PROBLEMATIC_PACKAGE_NAME.equals(name.trim())
                && KNOWN_PROBLEMATIC_PACKAGE_VERSION.equals(version.trim());
    }

    private static void logSkippingKnownProblematicPackage(String source, String coordinate) {
        if (source == null || source.isBlank()) {
            System.out.printf(Locale.ROOT, "Skipping known problematic package: %s%n", coordinate);
            return;
        }
        System.out.printf(Locale.ROOT, "Skipping known problematic package from %s: %s%n", source, coordinate);
    }

    private static void logSkippingKnownProblematicPackage(String source, String name, String version) {
        String coord = (version == null || version.isBlank()) ? name : (name + "@" + version);
        logSkippingKnownProblematicPackage(source, coord);
    }

    private static Optional<String> gatherFhirVersionFromSushi(Path file, String inlineYaml) throws IOException {
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

    static Optional<String> parseSushiFhirVersion(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) return Optional.empty();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root = yaml.load(yamlText);
        if (!(root instanceof Map<?, ?> map)) return Optional.empty();
        Object raw = map.get("fhirVersion");
        return extractFhirVersion(raw);
    }

    private static Optional<String> extractFhirVersion(Object node) {
        if (node == null) return Optional.empty();
        if (node instanceof String s) {
            String trimmed = s.trim();
            return trimmed.isBlank() ? Optional.empty() : Optional.of(trimmed);
        }
        if (node instanceof Collection<?> coll) {
            for (Object entry : coll) {
                if (entry == null) continue;
                String trimmed = entry.toString().trim();
                if (!trimmed.isBlank()) {
                    return Optional.of(trimmed);
                }
            }
            return Optional.empty();
        }
        if (node.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(node);
            for (int i = 0; i < length; i++) {
                Object entry = java.lang.reflect.Array.get(node, i);
                if (entry == null) continue;
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

    private record ParsedCoordinate(String name, String version) {}

    // --- Minimal JSON helpers (avoid full parse when only url/name/id are needed) ---

    private static String getUrlFromJson(String json) {
        String u = extractJsonString(json, "url");
        return (u != null) ? u : "";
    }

    private static String getNameFromJson(String json) {
        String n = extractJsonString(json, "name");
        return (n != null) ? n : "StructureDefinition";
    }

    private static String safeNameFromJson(String json) {
        String url = extractJsonString(json, "url");
        if (url != null && !url.isBlank()) {
            int i = url.lastIndexOf('/');
            String last = (i >= 0 && i < url.length() - 1) ? url.substring(i + 1) : url;
            return last.replaceAll("[^A-Za-z0-9._-]", "_");
        }
        String name = extractJsonString(json, "name");
        if (name != null && !name.isBlank()) return name.replaceAll("[^A-Za-z0-9._-]", "_");
        String id = extractJsonString(json, "id");
        if (id != null && !id.isBlank()) return id.replaceAll("[^A-Za-z0-9._-]", "_");
        return UUID.randomUUID().toString();
    }

    /**
     * Very small JSON string extractor for a top-level string field.
     */
    private static String extractJsonString(String json, String field) {
        String regex = "\\\"" + field + "\\\"\\s*:\\s*\\\"(.*?)\\\"";
        var m = Pattern.compile(regex, Pattern.DOTALL).matcher(json);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
