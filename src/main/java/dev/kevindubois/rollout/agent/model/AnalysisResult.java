package dev.kevindubois.rollout.agent.model;

public record AnalysisResult(
    boolean promote,
    int confidence,
    String analysis,
    String rootCause,
    String remediation,
    String prLink,
    String repoUrl,
    String baseBranch
) {
    public static AnalysisResult empty() {
        return new AnalysisResult(false, 0, "", "", "", null, null, null);
    }
    
    public AnalysisResult withPrLink(String newPrLink) {
        return new AnalysisResult(promote, confidence, analysis, rootCause, remediation, newPrLink, repoUrl, baseBranch);
    }
}
