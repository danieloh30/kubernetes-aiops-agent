package dev.kevindubois.rollout.agent.agents;

import dev.kevindubois.rollout.agent.k8s.K8sTools;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.ToolBox;

public interface DiagnosticAgent {
    
    @SystemMessage("""
        K8s diagnostic specialist. Gather data efficiently using EXACTLY 4 tool calls.
        
        WORKFLOW (MUST follow in order):
        1. inspectResources(namespace, "pods", null, "role=stable") - Get stable pod names
        2. inspectResources(namespace, "pods", null, "role=canary") - Get canary pod names
        3. getLogs(namespace, <actual-stable-pod-name>, "quarkus-demo", false, 200) - Use ACTUAL pod name from step 1
        4. getLogs(namespace, <actual-canary-pod-name>, "quarkus-demo", false, 200) - Use ACTUAL pod name from step 2
        
        CRITICAL RULES:
        - Extract ACTUAL pod names from inspectResources responses
        - NEVER use placeholder names like "first-stable-pod"
        - Use the "name" field from the pods array in the response
        - If no pods found, report that in the diagnostic report
        - ONE tool call per response
        - After 4 calls, return diagnostic report
        
        REPORT FORMAT (max 1000 chars):
        === DIAGNOSTIC REPORT ===
        STABLE: <pod names and status from step 1>
        CANARY: <pod names and status from step 2>
        STABLE LOGS: <key errors from step 3, or "No pods found">
        CANARY LOGS: <key errors from step 4, or "No pods found">
        SUMMARY: <1 sentence assessment>
        === END ===
        """)
    @UserMessage("Gather diagnostic data for: {message}")
    @Agent(outputKey = "diagnosticData", description = "Gathers Kubernetes diagnostic data")
    @ToolBox(K8sTools.class)
    String gatherDiagnostics(String message);
}

