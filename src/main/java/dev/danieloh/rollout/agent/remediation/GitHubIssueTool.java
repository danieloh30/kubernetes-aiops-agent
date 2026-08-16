package dev.danieloh.rollout.agent.remediation;

import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import io.quarkus.logging.Log;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Tool that creates GitHub issues for problems that need human attention.
 * Used when the agent identifies issues that cannot be automatically fixed.
 */
@ApplicationScoped
public class GitHubIssueTool {
    
    private final String githubToken;
    
    @Inject
    @RestClient
    GitHubRestClient githubClient;
    
    public GitHubIssueTool() {
        this.githubToken = System.getenv("GITHUB_TOKEN");
        if (githubToken == null || githubToken.isEmpty()) {
            Log.warn("GITHUB_TOKEN environment variable not set");
        } else {
            Log.debug("GitHub Issue tool initialized");
        }
    }
    
    /**
     * Create a GitHub issue to report a problem
     *
     * @param repoUrl URL of the GitHub repository
     * @param title Issue title
     * @param description Detailed description of the problem
     * @param rootCause Root cause analysis
     * @param namespace Kubernetes namespace
     * @param podName Kubernetes pod name
     * @param diagnosticSummary Additional diagnostic information (logs, events, metrics)
     * @param labels Comma-separated list of labels to apply
     * @param assignees Comma-separated list of GitHub usernames to assign
     * @return Result of the issue creation
     */
    @Tool("Create a GitHub issue to report a problem that needs human attention. Include detailed diagnostic information to help pinpoint the issue.")
    public Map<String, Object> createGitHubIssue(
            String repoUrl,
            String title,
            String description,
            String rootCause,
            String namespace,
            String podName,
            String diagnosticSummary,
            String labels,
            String assignees
    ) {
        Log.info("Executing Tool: createGitHubIssue");
        
        if (repoUrl == null || title == null || description == null) {
            return Map.of("success", false, "error", "Missing required parameters: repoUrl, title, description");
        }
        
        Log.info(MessageFormat.format("Creating issue for repository: {0}", repoUrl));
        
        try {
            // Extract owner and repo from URL
            String[] ownerRepo = extractOwnerAndRepo(repoUrl);
            String owner = ownerRepo[0];
            String repo = ownerRepo[1];
            String authHeader = "Bearer " + (githubToken != null ? githubToken : "");
            
            // Build issue body
            String issueBody = generateIssueBody(description, rootCause, namespace, podName, diagnosticSummary);
            
            // Parse labels and assignees with defensive sanitization
            // Strip out any brackets, quotes, or JSON-like formatting that LLM might add
            String sanitizedLabels = sanitizeInput(labels);
            String sanitizedAssignees = sanitizeInput(assignees);
            
            List<String> labelList = (sanitizedLabels != null && !sanitizedLabels.isEmpty())
                ? Arrays.stream(sanitizedLabels.split(","))
                    .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                    .filter(s -> !s.isEmpty())
                    .toList()
                : List.of();
            List<String> assigneeList = (sanitizedAssignees != null && !sanitizedAssignees.isEmpty())
                ? Arrays.stream(sanitizedAssignees.split(","))
                    .map(s -> s.trim().replaceAll("^@", "").replaceAll("^\"|\"$", ""))
                    .filter(s -> !s.isEmpty())
                    .toList()
                : List.of();
            
            // Create issue request
            GitHubRestClient.CreateIssueRequest issueRequest =
                new GitHubRestClient.CreateIssueRequest(title, issueBody, labelList, assigneeList);
            
            GitHubRestClient.GitHubIssue issue =
                githubClient.createIssue(owner, repo, authHeader, issueRequest);
            
            Log.info(MessageFormat.format("Successfully created issue: {0}", issue.html_url()));
            
            return Map.of(
                "success", true,
                "issueUrl", issue.html_url(),
                "issueNumber", issue.number()
            );
            
        } catch (Exception e) {
            Log.error("Failed to create issue", e);
            return Map.of(
                "success", false,
                "error", e.getMessage()
            );
        }
    }
    
    /**
     * Extract owner and repository name from URL
     * @return Array with [owner, repo]
     */
    private String[] extractOwnerAndRepo(String repoUrl) {
        // Handle formats: https://github.com/owner/repo or https://github.com/owner/repo.git
        String cleaned = repoUrl.replace("https://github.com/", "")
            .replace(".git", "");
        return cleaned.split("/", 2);
    }
    
    /**
     * Generate issue body with analysis results
     */
    private String generateIssueBody(
            String description,
            String rootCause,
            String namespace,
            String podName,
            String diagnosticSummary
    ) {
        if (rootCause == null || rootCause.isEmpty()) {
            rootCause = "Under investigation";
        }
        if (namespace == null) namespace = "unknown";
        if (podName == null) podName = "unknown";

        String severity = inferSeverity(rootCause);

        StringBuilder body = new StringBuilder();

        body.append(String.format("**Severity:** %s\n\n", severity));

        body.append("## Summary\n\n");
        body.append("A canary deployment was **automatically rolled back** after the AI analysis agent ");
        body.append("detected anomalies in the canary pods compared to stable pods.\n\n");

        body.append("## Root Cause\n\n");
        body.append(rootCause).append("\n\n");

        body.append("## What Happened\n\n");
        body.append(description).append("\n\n");

        if (diagnosticSummary != null && !diagnosticSummary.isEmpty()) {
            String extracted = extractStructuredDiagnostics(diagnosticSummary);
            if (!extracted.isEmpty()) {
                body.append("## Diagnostics\n\n");
                body.append(extracted).append("\n\n");
            }
        }

        body.append("## Environment\n\n");
        body.append(String.format("| Resource | Value |\n|---|---|\n"));
        body.append(String.format("| Namespace | `%s` |\n", namespace));
        body.append(String.format("| Pod | `%s` |\n", podName));
        body.append(String.format("| Detected by | Kubernetes AI Agent |\n"));
        body.append(String.format("| Action taken | Canary rollback |\n\n"));

        body.append("## Recommended Actions\n\n");
        body.append(generateRecommendedActions(rootCause));

        body.append("\n---\n");
        body.append("*Automatically created by [Kubernetes AI Agent](https://github.com/danieloh30/kubernetes-aiops-agent) — ");
        body.append("review diagnostics and take action.*\n");

        return body.toString();
    }

    private String inferSeverity(String rootCause) {
        String lower = rootCause.toLowerCase();
        if (lower.contains("oom") || lower.contains("out of memory") || lower.contains("crash")) {
            return ":red_circle: Critical";
        }
        if (lower.contains("timeout") || lower.contains("memory leak") || lower.contains("circuit breaker")
                || lower.contains("unresponsive") || lower.contains("degradation")) {
            return ":orange_circle: High";
        }
        return ":yellow_circle: Medium";
    }

    private String generateRecommendedActions(String rootCause) {
        String lower = rootCause.toLowerCase();
        StringBuilder actions = new StringBuilder();

        if (lower.contains("timeout") || lower.contains("downstream") || lower.contains("circuit breaker")) {
            actions.append("- [ ] Check health and logs of the downstream dependency (e.g., `inventory-service`)\n");
            actions.append("- [ ] Review network policies and service mesh configuration\n");
            actions.append("- [ ] Verify connection pool and timeout settings\n");
            actions.append("- [ ] Consider adding circuit breaker / retry with backoff if not present\n");
            actions.append("- [ ] Check if the downstream service is under-provisioned (CPU/memory limits)\n");
        } else if (lower.contains("memory") || lower.contains("oom") || lower.contains("heap")) {
            actions.append("- [ ] Capture a heap dump from a running pod (`jcmd <pid> GC.heap_dump /tmp/heap.hprof`)\n");
            actions.append("- [ ] Analyze heap dump for large object retention (Eclipse MAT or VisualVM)\n");
            actions.append("- [ ] Review recent code changes for unclosed resources or growing collections\n");
            actions.append("- [ ] Check JVM memory settings (`-Xmx`, `-XX:MaxRAMPercentage`)\n");
            actions.append("- [ ] Monitor GC activity (`-verbose:gc`, `-Xlog:gc*`)\n");
        } else if (lower.contains("cpu") || lower.contains("throttl")) {
            actions.append("- [ ] Review CPU requests/limits in the pod spec\n");
            actions.append("- [ ] Profile the application for CPU-intensive code paths\n");
            actions.append("- [ ] Check for runaway threads or tight loops\n");
        } else {
            actions.append("- [ ] Review canary pod logs for error patterns\n");
            actions.append("- [ ] Compare canary vs stable pod resource usage\n");
            actions.append("- [ ] Check recent code or config changes that may have introduced the issue\n");
        }

        actions.append("- [ ] Re-deploy after fix and monitor canary metrics\n");
        return actions.toString();
    }

    private String extractStructuredDiagnostics(String rawDiagnostics) {
        StringBuilder result = new StringBuilder();

        String[] lines = rawDiagnostics.split("\n");
        List<String> logLines = new ArrayList<>();
        boolean inLogSection = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (trimmed.contains("ERROR") || trimmed.contains("CRITICAL") || trimmed.contains("TIMEOUT")
                    || trimmed.contains("WARN") || trimmed.contains("Exception")) {
                logLines.add(trimmed);
                inLogSection = true;
            } else if (inLogSection && (trimmed.startsWith("at ") || trimmed.startsWith("Caused by:"))) {
                logLines.add(trimmed);
            } else {
                inLogSection = false;
            }
        }

        if (!logLines.isEmpty()) {
            result.append("<details>\n<summary>Key log lines from canary pods</summary>\n\n```\n");
            int maxLines = Math.min(logLines.size(), 20);
            for (int i = 0; i < maxLines; i++) {
                result.append(logLines.get(i)).append("\n");
            }
            if (logLines.size() > 20) {
                result.append("... (").append(logLines.size() - 20).append(" more lines)\n");
            }
            result.append("```\n</details>\n");
        }

        return result.toString();
    }
    
    /**
     * Sanitize input by removing JSON-like formatting that LLM might add
     * Strips brackets, quotes, and other special characters
     */
    private String sanitizeInput(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        
        // Remove square brackets, curly braces, and quotes
        // This handles cases like: ["label1","label2"], [label1,label2], "label1","label2"
        return input
            .replaceAll("[\\[\\]{}]", "")  // Remove brackets and braces
            .replaceAll("\"", "")           // Remove all quotes
            .replaceAll("'", "")            // Remove single quotes
            .trim();
    }
}
