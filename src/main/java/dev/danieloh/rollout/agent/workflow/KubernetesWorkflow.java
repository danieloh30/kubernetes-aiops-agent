package dev.danieloh.rollout.agent.workflow;

import dev.danieloh.rollout.agent.agents.DiagnosticAgent;
import dev.danieloh.rollout.agent.model.AnalysisResult;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.agentic.declarative.SequenceAgent;

public interface KubernetesWorkflow {

    @SequenceAgent(
        description = "Complete Kubernetes rollout analysis workflow",
        outputKey = "analysisResult",
        subAgents = {
            DiagnosticAgent.class,
            AnalysisLoop.class
        }
    )
    AnalysisResult execute(
        @MemoryId String memoryId,
        @UserMessage String message,
        String repoUrl,
        String baseBranch
    );
}
