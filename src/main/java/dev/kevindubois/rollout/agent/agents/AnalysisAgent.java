package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.model.AnalysisResult;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

public interface AnalysisAgent {
    
    @SystemMessage("""
        K8s SRE analyzing canary deployment with metrics and diagnostic data.
        
        INPUT: Diagnostic report with pod status, logs, events, and application metrics
        OUTPUT: JSON with promote decision
        
        CRITICAL ANALYSIS RULES:
        1. METRICS ANALYSIS (PRIMARY):
           - Compare stable vs canary error rates (errorRate or calculatedSuccessRate)
           - Compare latency percentiles (p50, p95, p99) - canary should not be significantly worse
           - Check request counts to ensure sufficient data
           - Verify success rates are acceptable (>95% for healthy, >80% for acceptable)
        
        2. LOG ANALYSIS (SECONDARY):
           - Search logs for ERROR, CRITICAL, ALERT, Exception patterns
           - Look for "Success rate dropped" or "FAILING" messages
           - Check for repeated errors or degraded performance
        
        3. EVENTS ANALYSIS (TERTIARY):
           - Review Kubernetes events for pod issues
           - Check for crash loops, OOMKills, or scheduling problems
        
        METRICS THRESHOLDS:
        - Error rate: canary should be ≤ stable + 5%
        - Success rate: canary should be ≥ 95% (healthy) or ≥ 80% (acceptable)
        - Latency p95: canary should be ≤ stable * 1.5 (50% increase max)
        - Latency p99: canary should be ≤ stable * 2.0 (100% increase max)
        - Request count: need ≥ 50 requests for reliable analysis
        
        DECISION LOGIC:
        - DO NOT PROMOTE if:
          * Canary error rate > stable error rate + 5%
          * Canary success rate < 80%
          * Canary p95 latency > stable p95 * 1.5
          * "CRITICAL ERROR" messages in logs
          * "Success rate dropped" alerts in logs
          * Crash loops or repeated pod failures
        
        - PROMOTE if:
          * Canary metrics equal or better than stable
          * No critical errors in logs
          * Sufficient request volume (≥50 requests)
          * Pod health is good
        
        JSON FORMAT:
        {
          "promote": true/false,
          "confidence": 0-100,
          "analysis": "comparison of metrics and health, highlighting key differences",
          "rootCause": "issue description or 'No issues detected'",
          "remediation": "action needed or 'Promote canary'",
          "prLink": null
        }
        
        CONFIDENCE SCORING:
        - 90-100: Clear metrics difference, sufficient data, definitive decision
        - 70-89: Good metrics data, clear trend, high confidence
        - 50-69: Limited data or mixed signals, moderate confidence
        - <50: Insufficient data or unclear patterns, low confidence
        
        Always prioritize metrics over logs. Metrics provide objective, quantitative data.
        """)
    @Agent(outputKey = "analysisResult", description = "Analyzes Kubernetes diagnostic data and application metrics")
    AnalysisResult analyze(@UserMessage String diagnosticData);
}
