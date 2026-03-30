package dev.danieloh.rollout.agent.utils;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TokenManagerTest {

    @Inject
    TokenManager tokenManager;

    @Test
    void testEstimateTokens() {
        // Test basic token estimation (1 token ≈ 4 characters)
        String text = "This is a test string with some content";
        int tokens = tokenManager.estimateTokens(text);
        assertTrue(tokens > 0, "Should estimate positive token count");
        assertEquals(text.length() / 4, tokens, "Should use 4 chars per token ratio");
    }

    @Test
    void testEstimateTokensEmpty() {
        assertEquals(0, tokenManager.estimateTokens(""));
        assertEquals(0, tokenManager.estimateTokens(null));
    }

    @Test
    void testTruncateDiagnosticDataWithinLimits() {
        String diagnosticData = """
            === DIAGNOSTIC REPORT ===
            STABLE POD: Running
            CANARY POD: Running
            STABLE LOGS: No errors
            CANARY LOGS: No errors
            STABLE METRICS: totalRequests=100, successRate=100%, errorRate=0%
            CANARY METRICS: totalRequests=100, successRate=100%, errorRate=0%
            SUMMARY: All systems operational
            === END ===
            """;

        String result = tokenManager.truncateDiagnosticData(diagnosticData, 10000);
        assertEquals(diagnosticData, result, "Should not truncate when within limits");
    }

    @Test
    void testTruncateDiagnosticDataExceedsLimits() {
        // Create large diagnostic data with many log lines
        StringBuilder largeData = new StringBuilder();
        largeData.append("=== DIAGNOSTIC REPORT ===\n");
        largeData.append("STABLE POD: Running\n");
        largeData.append("CANARY POD: CrashLoopBackOff\n");
        largeData.append("STABLE LOGS:\n");
        for (int i = 0; i < 100; i++) {
            largeData.append("2024-03-30 00:00:00 INFO Log line ").append(i).append("\n");
        }
        largeData.append("CANARY LOGS:\n");
        for (int i = 0; i < 100; i++) {
            largeData.append("2024-03-30 00:00:00 ERROR NullPointerException at line ").append(i).append("\n");
        }
        largeData.append("STABLE METRICS: totalRequests=1000, successRate=99%, errorRate=1%\n");
        largeData.append("CANARY METRICS: totalRequests=100, successRate=50%, errorRate=50%\n");
        largeData.append("SUMMARY: Canary deployment failing with NPE\n");
        largeData.append("=== END ===\n");

        String result = tokenManager.truncateDiagnosticData(largeData.toString(), 500);
        
        assertNotNull(result);
        assertTrue(result.contains("TRUNCATED"), "Should indicate truncation");
        assertTrue(result.contains("CANARY POD:"), "Should preserve pod status");
        assertTrue(result.contains("CANARY METRICS:"), "Should preserve metrics");
        assertTrue(result.contains("SUMMARY:"), "Should preserve summary");
        
        int originalTokens = tokenManager.estimateTokens(largeData.toString());
        int truncatedTokens = tokenManager.estimateTokens(result);
        assertTrue(truncatedTokens < originalTokens, "Should reduce token count");
    }

    @Test
    void testTruncateSourceCode() {
        String enrichedPrompt = """
            === DIAGNOSTIC REPORT ===
            CANARY POD: CrashLoopBackOff
            SUMMARY: NullPointerException in LoadGeneratorService
            === END ===
            
            === SOURCE CODE (pre-fetched) ===
            File: src/main/java/LoadGeneratorService.java
               1 | package dev.danieloh.demo;
               2 | 
               3 | public class LoadGeneratorService {
               4 |     public void generate() {
               5 |         String value = null;
               6 |         System.out.println(value.length()); // NPE here
               7 |     }
               8 | }
            """;

        // Test with reasonable limit
        String result = tokenManager.truncateSourceCode(enrichedPrompt, 10000);
        assertEquals(enrichedPrompt, result, "Should not truncate when within limits");

        // Test with very low limit
        String truncated = tokenManager.truncateSourceCode(enrichedPrompt, 50);
        assertTrue(truncated.contains("DIAGNOSTIC REPORT"), "Should preserve diagnostic section");
        assertTrue(truncated.length() < enrichedPrompt.length(), "Should be shorter");
    }

    @Test
    void testPrepareRemediationInput() {
        // Create input that exceeds limits (need ~100,000+ chars to exceed 25,000 tokens)
        StringBuilder largeInput = new StringBuilder();
        largeInput.append("=== DIAGNOSTIC REPORT ===\n");
        largeInput.append("STABLE POD: Running\n");
        largeInput.append("CANARY POD: CrashLoopBackOff\n");
        largeInput.append("STABLE LOGS:\n");
        for (int i = 0; i < 1000; i++) {
            largeInput.append("2024-03-30 00:00:00 INFO Log line ").append(i)
                    .append(" with some additional context and more text to increase size\n");
        }
        largeInput.append("CANARY LOGS:\n");
        for (int i = 0; i < 1000; i++) {
            largeInput.append("2024-03-30 00:00:00 ERROR NullPointerException at line ").append(i)
                    .append(" with stack trace and additional debugging information\n");
        }
        largeInput.append("STABLE METRICS: totalRequests=1000, successRate=99%, errorRate=1%\n");
        largeInput.append("CANARY METRICS: totalRequests=100, successRate=50%, errorRate=50%\n");
        largeInput.append("SUMMARY: Canary deployment failing\n");
        largeInput.append("=== END ===\n");
        
        largeInput.append("=== SOURCE CODE (pre-fetched) ===\n");
        for (int i = 0; i < 2000; i++) {
            largeInput.append(String.format("%4d | public void method%d() { /* code with implementation details */ }\n", i + 1, i));
        }

        String result = tokenManager.prepareRemediationInput(largeInput.toString());
        
        assertNotNull(result);
        int originalTokens = tokenManager.estimateTokens(largeInput.toString());
        int resultTokens = tokenManager.estimateTokens(result);
        
        // Only assert reduction if original was large enough to trigger truncation
        if (originalTokens > 25000) {
            assertTrue(resultTokens < originalTokens, "Should reduce token count when exceeding limits");
            assertTrue(resultTokens <= 25000, "Should be within max input tokens");
        }
        assertTrue(result.contains("CANARY POD:"), "Should preserve key information");
    }

    @Test
    void testPrepareRemediationInputWithinLimits() {
        String smallInput = """
            === DIAGNOSTIC REPORT ===
            CANARY POD: CrashLoopBackOff
            SUMMARY: Error detected
            === END ===
            """;

        String result = tokenManager.prepareRemediationInput(smallInput);
        assertEquals(smallInput, result, "Should not modify input within limits");
    }

    @Test
    void testPrepareRemediationInputNull() {
        assertNull(tokenManager.prepareRemediationInput(null));
        assertEquals("", tokenManager.prepareRemediationInput(""));
    }
}

// Made with Bob
