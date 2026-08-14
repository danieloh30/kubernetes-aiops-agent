# Kubernetes AIOps Agent

A Digital SRE Agent that uses LLMs to autonomously analyze Kubernetes canary deployments, make promote/rollback decisions, and create GitHub PRs or Issues with automated remediation. Built with Quarkus and LangChain4j.

## How It Works

The agent exposes an A2A (Agent-to-Agent) protocol endpoint that receives canary analysis requests -- typically from an Argo Rollouts analysis plugin. Internally, it runs a multi-agent workflow:

```
Argo Rollouts AnalysisTemplate
  |
  v
rollouts-plugin-metric-ai  (Argo plugin)
  |  POST /a2a/analyze
  v
Kubernetes AIOps Agent
  |
  |-- DiagnosticAgent: Gathers pod status, logs, and /q/metrics from stable + canary pods
  |-- MetricsDiagnosticAgent: Fetches application-level metrics (error rates, latency, success rates)
  |-- AnalysisAgent: Compares stable vs. canary using thresholds (error rate, p95/p99 latency, success rate)
  |-- ScoringAgent: Evaluates analysis quality; retries if confidence is too low
  |-- RemediationAgent (async, on rollback):
  |     - Code bug detected  -->  Creates GitHub PR with a patch fix
  |     - Operational issue  -->  Creates GitHub Issue with RCA
  v
JSON response: { promote, confidence, analysis, rootCause, remediation, prLink }
```

The workflow is defined declaratively via LangChain4j `@SequenceAgent` and `@Agent` annotations. Each sub-agent has its own system prompt, tools, and output key.

## Configuration

### LLM Providers

The agent supports multiple LLM providers, selectable via Quarkus profiles:

| Profile | Provider | Default Model | API Key Env Var |
|---------|----------|---------------|-----------------|
| `gemini` | Google Gemini | `gemini-2.5-flash` | `GOOGLE_API_KEY` |
| `openai` | OpenAI (or compatible) | `gpt-4o` | `OPENAI_API_KEY` |

The `RemediationAgent` uses a separate named model configuration (`remediation`) to allow a different provider/model for code generation tasks.

### Environment Variables

| Variable | Required | Description |
|----------|----------|-------------|
| `GOOGLE_API_KEY` | Yes (gemini profile) | Google Gemini API key |
| `OPENAI_API_KEY` | Yes (openai profile) | OpenAI API key |
| `GITHUB_TOKEN` | Yes | GitHub PAT with `repo` scope (for PR/Issue creation) |
| `GEMINI_MODEL` | No | Override Gemini model (default: `gemini-2.5-flash`) |
| `OPENAI_MODEL` | No | Override OpenAI model (default: `gpt-4o`) |
| `OPENAI_BASE_URL` | No | OpenAI-compatible base URL (default: `https://api.openai.com/v1`) |
| `REM_API_KEY` | No | Separate API key for RemediationAgent |
| `REM_BASE_URL` | No | Separate base URL for RemediationAgent |
| `REM_MODEL` | No | Separate model for RemediationAgent (default: `gpt-4o`) |
| `GIT_USERNAME` | No | Git commit author (default: `kubernetes-agent`) |
| `GIT_EMAIL` | No | Git commit email (default: `agent@example.com`) |

### Kubernetes Secrets

In production, API keys are read from a Kubernetes Secret named `kubernetes-agent` in the `openshift-gitops` namespace. See `deployment/secret.yaml.template` for the format.

## Local Development

### Prerequisites

- Java 21+
- Maven 3.8+
- Access to a Kubernetes cluster (for K8s tools to function)

### Running

```bash
# Set API keys
export GOOGLE_API_KEY="..."   # or OPENAI_API_KEY
export GITHUB_TOKEN="..."

# Run with Gemini
mvn quarkus:dev -Dquarkus.profile=dev,gemini

# Run with OpenAI
mvn quarkus:dev -Dquarkus.profile=dev,openai

# Run with a vLLM-compatible endpoint
export OPENAI_BASE_URL="http://vllm-host:8000/v1"
export OPENAI_API_KEY="dummy"
mvn quarkus:dev -Dquarkus.profile=dev,openai
```

### Testing the Endpoint

```bash
curl -X POST http://localhost:8080/a2a/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test",
    "prompt": "Analyze canary deployment",
    "context": {
      "namespace": "default",
      "podName": "my-app-canary",
      "repoUrl": "https://github.com/owner/repo",
      "baseBranch": "main"
    }
  }'
```

### Running Tests

```bash
mvn test                          # Unit tests
mvn verify -DskipITs=false        # Integration tests
```

## Building and Deploying

### Build the Container Image

```bash
# Build with Maven + Podman
mvn clean package -Dquarkus.container-image.build=true -Dquarkus.profile=prod,gemini

# Push to registry
mvn quarkus:image-push -Dquarkus.container-image.build=true -Dquarkus.profile=prod,gemini
```

The image is published to `quay.io/danieloh30/kubernetes-agent:latest`.

### Deploy to OpenShift

```bash
# Create the secret from template
cp deployment/secret.yaml.template deployment/secret.yaml
# Edit secret.yaml with your API keys, then:
kubectl apply -f deployment/secret.yaml

# Deploy all resources
kubectl apply -k deployment/

# Verify
kubectl get pods -n openshift-gitops | grep kubernetes-agent
```

The deployment manifests live in `deployment/` and include:
- `deployment.yaml` -- Deployment spec
- `service.yaml` -- ClusterIP Service
- `rbac.yaml` -- ServiceAccount and RBAC (read-only K8s access)
- `secret.yaml.template` -- Secret template for API keys
- `kustomization.yaml` -- Kustomize overlay (namespace: `openshift-gitops`)

## Integration with Argo Rollouts

The agent is designed to work with `rollouts-plugin-metric-ai`, an Argo Rollouts metric plugin that delegates canary analysis to this agent.

### AnalysisTemplate Example

```yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: canary-analysis-with-agent
spec:
  metrics:
    - name: ai-analysis
      provider:
        plugin:
          ai-metric:
            analysisMode: agent
            namespace: "{{args.namespace}}"
            podName: "{{args.canary-pod}}"
            stablePodLabel: app=rollouts-demo,role=stable
            canaryPodLabel: app=rollouts-demo,role=canary
```

When the Argo Rollouts analysis runs, the plugin sends a `POST /a2a/analyze` request to the agent. The agent gathers diagnostics from the cluster, analyzes them with an LLM, and returns a promote/rollback decision. If the decision is to roll back, the agent asynchronously creates a GitHub PR (for code bugs) or a GitHub Issue (for operational problems like memory leaks).

## Project Structure

```
src/main/java/dev/danieloh/rollout/agent/
  a2a/                   A2A protocol endpoint and agent card
  agents/                LangChain4j agent interfaces (Diagnostic, Analysis, Scoring, Remediation)
  k8s/                   Kubernetes tools (pod logs, metrics, events, resources)
  model/                 Data records (AnalysisResult, KubernetesAgentRequest/Response)
  remediation/           GitHub PR and Issue creation tools (JGit + GitHub REST API)
  service/               Response parsing and JSON utilities
  utils/                 Rate limiter, retry helper, token manager
  workflow/              Declarative multi-agent workflow (SequenceAgent)
deployment/              Kubernetes/OpenShift manifests
```
