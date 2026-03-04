package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.kevindubois.rollout.agent.remediation.GitHubPRTool;
import dev.kevindubois.rollout.agent.remediation.GitHubIssueTool;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface RemediationAgent {
    
    @SystemMessage("""
        Remediation specialist. Implement fixes based on analysis.
        
        DECISION LOGIC:
        1. If promote=false AND repoUrl provided → ALWAYS create GitHub issue to track the problem
        2. If specific code fix identified + repoUrl provided → createGitHubPR(repoUrl, baseBranch, ...)
        3. If repoUrl is null/empty → return AnalysisResult unchanged
        
        GITHUB ISSUE CREATION - EXACT PARAMETER FORMAT:
        When calling createGitHubIssue, use these EXACT formats:
        
        labels: "deployment-failure,canary"
        ✓ CORRECT: "deployment-failure,canary"
        ✗ WRONG: "[\\"deployment-failure\\",\\"canary\\"]"
        ✗ WRONG: "[deployment-failure,canary]"
        ✗ WRONG: "deployment-failure", "canary"
        ✗ WRONG: "canary"]
        ✗ WRONG: ["deployment-failure"
        
        assignees: "kdubois"
        ✓ CORRECT: "kdubois"
        ✗ WRONG: "@kdubois"
        
        Other parameters:
        - repoUrl: Use the provided repository URL exactly as given
        - title: "Canary Deployment Failed: [brief root cause from analysis]"
        - description: Use the analysis field content
        - rootCause: Use the rootCause field content
        - namespace: Extract from diagnosticData (look for namespace field or pod names)
        - podName: Extract canary pod name from diagnosticData
        
        CRITICAL RULES:
        - labels MUST be plain comma-separated text with NO special characters, NO brackets, NO quotes around individual labels
        - assignees MUST be plain username with NO @ symbol
        - Make ONE tool call attempt only - do not retry on failure with different formats
        - If tool fails, return error immediately - do NOT try alternative formats
        
        RULES:
        - NO fake URLs (example.com, placeholder URLs)
        - Return actual PR/issue URL from tool or null
        - Always create issue for failed deployments when repoUrl is available
        """)
    @UserMessage("""
        Diagnostic data: {diagnosticData}
        
        Analysis result: {analysisResult}
        Repository URL: {repoUrl}
        Base branch: {baseBranch}
        
        Implement remediation if needed and return the updated AnalysisResult with prLink set if a PR was created.
        Extract namespace, rolloutName, and pod names from the diagnostic data to use when creating GitHub issues.
        """)
    @Agent(outputKey = "finalResult", description = "Implements remediation fixes")
    @ToolBox({GitHubPRTool.class, GitHubIssueTool.class})
    AnalysisResult implementRemediation(
        String diagnosticData,
        AnalysisResult analysisResult,
        String repoUrl,
        String baseBranch
    );
}

