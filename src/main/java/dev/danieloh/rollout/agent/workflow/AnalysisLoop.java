package dev.danieloh.rollout.agent.workflow;

import dev.danieloh.rollout.agent.agents.AnalysisAgent;
import dev.danieloh.rollout.agent.agents.ScoringAgent;
import dev.danieloh.rollout.agent.model.AnalysisResult;
import dev.danieloh.rollout.agent.model.ScoringResult;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.declarative.LoopAgent;
import dev.langchain4j.agentic.declarative.ExitCondition;

public interface AnalysisLoop {
    
    @LoopAgent(
        description = "Analyze Kubernetes diagnostics with retry until confidence threshold is met",
        outputKey = "analysisResult",
        maxIterations = 3,
        subAgents = {AnalysisAgent.class, ScoringAgent.class}
    )
    AnalysisResult analyzeWithRetry(String diagnosticData);
    
    @ExitCondition
    static boolean shouldExit(AgenticScope scope) {
        ScoringResult scoring = (ScoringResult) scope.readState("scoringResult");
        return scoring != null && !scoring.needsRetry();
    }
}

