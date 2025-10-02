package de.gefyra.fhirpkg;

import java.nio.file.Paths;

public class DebugEnvironment {
    public static void main(String[] args) {
        System.out.println("=== Environment Debug ===");
        System.out.println("user.home: " + System.getProperty("user.home"));
        System.out.println("GITHUB_ACTIONS: " + System.getenv("GITHUB_ACTIONS"));
        System.out.println("HOME: " + System.getenv("HOME"));
        System.out.println("GITHUB_WORKSPACE: " + System.getenv("GITHUB_WORKSPACE"));
        System.out.println("RUNNER_WORKSPACE: " + System.getenv("RUNNER_WORKSPACE"));
        
        // Manual logic check
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            System.out.println("Would use APPDATA: " + Paths.get(appData, "fhir", "packages"));
            return;
        }
        
        String githubActions = System.getenv("GITHUB_ACTIONS");
        if ("true".equals(githubActions)) {
            String home = System.getenv("HOME");
            if (home != null && !home.isBlank()) {
                System.out.println("GitHub Actions detected! Would use HOME: " + Paths.get(home, ".fhir", "packages"));
                return;
            }
        }
        
        System.out.println("Would use user.home: " + Paths.get(System.getProperty("user.home"), ".fhir", "packages"));
    }
}