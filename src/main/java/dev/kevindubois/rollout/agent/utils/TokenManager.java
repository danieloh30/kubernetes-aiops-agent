package dev.kevindubois.rollout.agent.utils;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.text.MessageFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for managing token limits and truncating input data.
 * Helps prevent rate limit errors by ensuring input stays within model limits.
 */
@ApplicationScoped
public class TokenManager {
    
    // Approximate token estimation: 1 token ≈ 4 characters for English text
    private static final int CHARS_PER_TOKEN = 4;
    
    // Conservative limits to stay well under API limits
    private static final int MAX_INPUT_TOKENS = 25000; // Leave room for output tokens
    private static final int MAX_DIAGNOSTIC_TOKENS = 15000;
    private static final int MAX_SOURCE_CODE_TOKENS = 8000;
    
    /**
     * Estimate token count from text length.
     * This is a rough approximation - actual tokenization may vary.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }
    
    /**
     * Truncate diagnostic data to fit within token limits while preserving key information.
     * Prioritizes error messages, metrics, and recent log entries.
     */
    public String truncateDiagnosticData(String diagnosticData, int maxTokens) {
        if (diagnosticData == null || diagnosticData.isEmpty()) {
            return diagnosticData;
        }
        
        int estimatedTokens = estimateTokens(diagnosticData);
        if (estimatedTokens <= maxTokens) {
            Log.debug(MessageFormat.format("Diagnostic data within limits: {0} tokens", estimatedTokens));
            return diagnosticData;
        }
        
        Log.info(MessageFormat.format("Truncating diagnostic data from {0} to {1} tokens", 
                estimatedTokens, maxTokens));
        
        // Extract key sections
        String summary = extractSection(diagnosticData, "SUMMARY:", "===");
        String stablePod = extractSection(diagnosticData, "STABLE POD:", "CANARY POD:");
        String canaryPod = extractSection(diagnosticData, "CANARY POD:", "STABLE LOGS:");
        String stableMetrics = extractSection(diagnosticData, "STABLE METRICS:", "CANARY METRICS:");
        String canaryMetrics = extractSection(diagnosticData, "CANARY METRICS:", "SUMMARY:");
        
        // Extract and truncate logs (most verbose section)
        String stableLogs = extractSection(diagnosticData, "STABLE LOGS:", "CANARY LOGS:");
        String canaryLogs = extractSection(diagnosticData, "CANARY LOGS:", "STABLE METRICS:");
        
        // Truncate logs to last N lines (most recent errors)
        stableLogs = truncateToLastLines(stableLogs, 20);
        canaryLogs = truncateToLastLines(canaryLogs, 20);
        
        // Reconstruct truncated diagnostic data
        StringBuilder truncated = new StringBuilder();
        truncated.append("=== DIAGNOSTIC REPORT (TRUNCATED) ===\n");
        truncated.append("STABLE POD: ").append(stablePod).append("\n");
        truncated.append("CANARY POD: ").append(canaryPod).append("\n");
        truncated.append("STABLE LOGS: ").append(stableLogs).append("\n");
        truncated.append("CANARY LOGS: ").append(canaryLogs).append("\n");
        truncated.append("STABLE METRICS: ").append(stableMetrics).append("\n");
        truncated.append("CANARY METRICS: ").append(canaryMetrics).append("\n");
        truncated.append("SUMMARY: ").append(summary).append("\n");
        truncated.append("=== END ===\n");
        
        String result = truncated.toString();
        int finalTokens = estimateTokens(result);
        
        // If still too large, aggressively truncate logs
        if (finalTokens > maxTokens) {
            stableLogs = truncateToLastLines(stableLogs, 10);
            canaryLogs = truncateToLastLines(canaryLogs, 10);
            
            truncated = new StringBuilder();
            truncated.append("=== DIAGNOSTIC REPORT (HEAVILY TRUNCATED) ===\n");
            truncated.append("STABLE POD: ").append(stablePod).append("\n");
            truncated.append("CANARY POD: ").append(canaryPod).append("\n");
            truncated.append("STABLE LOGS: ").append(stableLogs).append("\n");
            truncated.append("CANARY LOGS: ").append(canaryLogs).append("\n");
            truncated.append("STABLE METRICS: ").append(stableMetrics).append("\n");
            truncated.append("CANARY METRICS: ").append(canaryMetrics).append("\n");
            truncated.append("SUMMARY: ").append(summary).append("\n");
            truncated.append("=== END ===\n");
            
            result = truncated.toString();
            finalTokens = estimateTokens(result);
        }
        
        Log.info(MessageFormat.format("Truncated diagnostic data to {0} tokens", finalTokens));
        return result;
    }
    
    /**
     * Truncate source code section to fit within token limits.
     * Preserves line numbers and structure.
     */
    public String truncateSourceCode(String enrichedPrompt, int maxTokens) {
        if (enrichedPrompt == null || enrichedPrompt.isEmpty()) {
            return enrichedPrompt;
        }
        
        // Check if source code section exists
        if (!enrichedPrompt.contains("=== SOURCE CODE (pre-fetched) ===")) {
            return enrichedPrompt;
        }
        
        int estimatedTokens = estimateTokens(enrichedPrompt);
        if (estimatedTokens <= maxTokens) {
            return enrichedPrompt;
        }
        
        Log.info(MessageFormat.format("Truncating source code from {0} to {1} tokens", 
                estimatedTokens, maxTokens));
        
        // Split into diagnostic and source code sections
        String[] parts = enrichedPrompt.split("=== SOURCE CODE \\(pre-fetched\\) ===", 2);
        String diagnosticPart = parts[0];
        String sourceCodePart = parts.length > 1 ? parts[1] : "";
        
        // Calculate available tokens for source code
        int diagnosticTokens = estimateTokens(diagnosticPart);
        int availableForSource = maxTokens - diagnosticTokens - 100; // Buffer
        
        if (availableForSource < 1000) {
            // Not enough room for meaningful source code, remove it
            Log.warn("Insufficient tokens for source code, removing section");
            return diagnosticPart + "\n[SOURCE CODE OMITTED DUE TO TOKEN LIMITS]\n";
        }
        
        // Truncate source code to available tokens
        int maxSourceChars = availableForSource * CHARS_PER_TOKEN;
        if (sourceCodePart.length() > maxSourceChars) {
            sourceCodePart = sourceCodePart.substring(0, maxSourceChars) + 
                           "\n... [TRUNCATED DUE TO TOKEN LIMITS] ...\n";
        }
        
        String result = diagnosticPart + "=== SOURCE CODE (pre-fetched) ===" + sourceCodePart;
        Log.info(MessageFormat.format("Final token count: {0}", estimateTokens(result)));
        
        return result;
    }
    
    /**
     * Prepare input for RemediationAgent by ensuring it fits within token limits.
     */
    public String prepareRemediationInput(String enrichedPrompt) {
        if (enrichedPrompt == null || enrichedPrompt.isEmpty()) {
            return enrichedPrompt;
        }
        
        int estimatedTokens = estimateTokens(enrichedPrompt);
        
        if (estimatedTokens <= MAX_INPUT_TOKENS) {
            Log.debug(MessageFormat.format("Input within limits: {0} tokens", estimatedTokens));
            return enrichedPrompt;
        }
        
        Log.warn(MessageFormat.format("Input exceeds limits: {0} tokens, truncating to {1}", 
                estimatedTokens, MAX_INPUT_TOKENS));
        
        // First, truncate diagnostic data
        String result = enrichedPrompt;
        if (enrichedPrompt.contains("=== DIAGNOSTIC REPORT ===")) {
            String diagnosticSection = extractSection(enrichedPrompt, 
                    "=== DIAGNOSTIC REPORT ===", "=== SOURCE CODE");
            String truncatedDiagnostic = truncateDiagnosticData(diagnosticSection, MAX_DIAGNOSTIC_TOKENS);
            result = enrichedPrompt.replace(diagnosticSection, truncatedDiagnostic);
        }
        
        // Then, truncate source code if still too large
        estimatedTokens = estimateTokens(result);
        if (estimatedTokens > MAX_INPUT_TOKENS) {
            result = truncateSourceCode(result, MAX_INPUT_TOKENS);
        }
        
        int finalTokens = estimateTokens(result);
        Log.info(MessageFormat.format("Prepared remediation input: {0} tokens (reduced from {1})", 
                finalTokens, estimateTokens(enrichedPrompt)));
        
        return result;
    }
    
    /**
     * Extract a section of text between two markers.
     */
    private String extractSection(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start == -1) {
            return "";
        }
        start += startMarker.length();
        
        int end = text.indexOf(endMarker, start);
        if (end == -1) {
            end = text.length();
        }
        
        return text.substring(start, end).trim();
    }
    
    /**
     * Truncate text to last N lines (most recent entries).
     */
    private String truncateToLastLines(String text, int maxLines) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        String[] lines = text.split("\n");
        if (lines.length <= maxLines) {
            return text;
        }
        
        StringBuilder result = new StringBuilder();
        result.append("[... ").append(lines.length - maxLines).append(" earlier lines omitted ...]\n");
        
        for (int i = lines.length - maxLines; i < lines.length; i++) {
            result.append(lines[i]).append("\n");
        }
        
        return result.toString();
    }
}

// Made with Bob
