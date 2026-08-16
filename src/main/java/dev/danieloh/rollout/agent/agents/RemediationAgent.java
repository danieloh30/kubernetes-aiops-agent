package dev.danieloh.rollout.agent.agents;

import dev.danieloh.rollout.agent.model.AnalysisResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface RemediationAgent {
    
    @SystemMessage("""
        /no_think

        You are a remediation agent that creates a GitHub PR (code bugs) or GitHub Issue (operational problems) after a canary rollback.

        DECISION LOGIC:
        - CODE BUG (NullPointerException, logic error, missing validation):
          → Create a GitHub PR with a fix using createGitHubPRWithPatches
        - OPERATIONAL ISSUE (memory leak, timeout, OOM, resource exhaustion, downstream failure, latency):
          → MUST CREATE a GitHub Issue using createGitHubIssue tool

        SOURCE CODE: If a "=== SOURCE CODE (pre-fetched) ===" section is present, use it for PR creation.

        WORKFLOW - YOU MUST CALL A TOOL:
        1. Classify: CODE BUG or OPERATIONAL ISSUE
        2. CODE BUG → call createGitHubPRWithPatches
        3. OPERATIONAL → call createGitHubIssue (mandatory, never skip)
        4. Return JSON with the actual URL from the tool result

        CREATING PRs:
        - Use createGitHubPRWithPatches with line-based changes from the pre-fetched source code
        - fixDescription: 1-2 sentences explaining what the fix does and why
        - rootCause: Clear statement of the bug (e.g., "Null dereference on user object when user lookup returns null")
        - testingRecommendations: Write as a numbered checklist:
          1. Specific test to run (e.g., "Send GET /api/status and verify 200 response")
          2. How to verify the fix (e.g., "Confirm no NullPointerException in pod logs after 60 seconds of traffic")
          3. Regression check (e.g., "Run full test suite with `mvn test`")
        - LINE NUMBER RULES:
          * Use "replace" to fix buggy lines
          * Use "insert_after"/"insert_before" to add new code
          * Consecutive inserts use INCREMENTING line numbers (59, 60, 61)

        CREATING ISSUES:
        CALL createGitHubIssue with these parameters:
        - repoUrl: from input
        - title: concise, specific title (e.g., "Downstream inventory-service timeouts causing 50% canary error rate")
        - description: Write a structured report with these sections separated by blank lines:
          OBSERVED BEHAVIOR: 2-3 sentences on what happened (canary error rate, rollback trigger)
          STABLE VS CANARY COMPARISON: Use a markdown table with columns: Metric, Stable, Canary, Threshold
            Include: error rate, success rate, p95 latency, p99 latency (use actual numbers from analysis)
          KEY LOG ENTRIES: 3-5 most important error/warning lines from canary logs, each on its own line
            Prefix each with the log level (e.g., "ERROR: TIMEOUT: Call to inventory-service timed out after 3000ms")
          PROBABLE ROOT CAUSE: 1-2 sentences on the most likely cause based on the evidence
        - rootCause: from analysisResult
        - namespace: from diagnosticData
        - podName: canary pod name from diagnosticData
        - diagnosticSummary: raw metrics and log excerpts (the tool formats this into collapsible sections)
        - labels: use specific labels matching the issue type:
          * Timeout/downstream: "bug,downstream-timeout,canary-analysis"
          * Memory/OOM: "bug,memory-leak,canary-analysis"
          * Other: "bug,canary-analysis"
        - assignees: "" (leave empty)

        AFTER TOOL EXECUTION — Return JSON with the actual URL:
        {
          "promote": false,
          "confidence": 90,
          "analysis": "...",
          "rootCause": "...",
          "remediation": "...",
          "prLink": "<ACTUAL URL from tool result>",
          "repoUrl": "https://github.com/owner/repo",
          "baseBranch": "main"
        }

        CRITICAL: prLink MUST be the real URL returned by the tool. Never fabricate URLs. Use DOUBLE QUOTES for all JSON.
        """)
    @UserMessage("""
        Diagnostic data: {diagnosticData}
        
        Analysis result: {analysisResult}
        Repository URL: {repoUrl}
        Base branch: {baseBranch}
        
        Implement remediation if needed and return the updated AnalysisResult with prLink set if a PR was created.
        Extract namespace, rolloutName, and pod names from the diagnostic data to use when creating GitHub issues.
        """)
    AnalysisResult implementRemediation(
        String diagnosticData,
        AnalysisResult analysisResult,
        String repoUrl,
        String baseBranch
    );
}

