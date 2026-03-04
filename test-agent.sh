#!/bin/bash
# Test script for Kubernetes AI Agent
# Supports both Kubernetes and local modes
# Usage:
#   ./test-agent.sh k8s     # Test agent running in Kubernetes (default)
#   ./test-agent.sh local   # Test agent running locally on localhost:8080

set -e

# Parse mode argument
MODE="${1:-k8s}"
if [[ "$MODE" != "k8s" && "$MODE" != "local" ]]; then
	echo "❌ Invalid mode: $MODE"
	echo "Usage: $0 [k8s|local]"
	echo "  k8s   - Test agent running in Kubernetes (default)"
	echo "  local - Test agent running locally on localhost:8080"
	exit 1
fi

# Configuration
AGENT_NAMESPACE="openshift-gitops"
GITHUB_REPO="${GITHUB_REPO:-kdubois/argo-rollouts-quarkus-demo}"
TEST_NAMESPACE="${NAMESPACE:-quarkus-demo}"
TEST_POD_NAME="${POD_NAME:-quarkus-demo-canary-abc123}"
CONTEXT="${CONTEXT:-}"  # Use current context if not specified
LOCAL_URL="${LOCAL_URL:-http://localhost:8080}"

function _kubectl() {
	if [[ -n "$CONTEXT" ]]; then
		kubectl --context "$CONTEXT" -n "$AGENT_NAMESPACE" "$@"
	else
		kubectl -n "$AGENT_NAMESPACE" "$@"
	fi
}

function _curl() {
	if [[ "$MODE" == "local" ]]; then
		curl --connect-timeout 5 --max-time 10 "$@"
	else
		_kubectl exec deployment/kubernetes-agent -- curl --connect-timeout 5 --max-time 10 "$@"
	fi
}

function _get_base_url() {
	if [[ "$MODE" == "local" ]]; then
		echo "$LOCAL_URL"
	else
		# Use localhost when curling from within the same pod for faster response
		echo "http://localhost:8080"
	fi
}

echo "🧪 Testing Kubernetes AI Agent"
echo "📍 Mode: $MODE"
if [[ "$MODE" == "local" ]]; then
	echo "🔗 URL: $LOCAL_URL"
else
	if [[ -n "$CONTEXT" ]]; then
		echo "☸️  Context: $CONTEXT"
	else
		CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "default")
		echo "☸️  Context: $CURRENT_CONTEXT (current)"
	fi
	echo "📦 Namespace: $AGENT_NAMESPACE"
fi
echo ""

# Test 1: Health Check
echo "1️⃣  Testing health endpoint..."
BASE_URL=$(_get_base_url)
health_url="$BASE_URL/q/health"
if ! HEALTH_RESPONSE=$(_curl -sf "$health_url"); then
	echo "❌ Health check failed ($health_url). Agent may not be ready."
	if [[ "$MODE" == "k8s" ]]; then
		if [[ -n "$CONTEXT" ]]; then
			echo "   Check agent logs: kubectl logs --context $CONTEXT -n $AGENT_NAMESPACE deployment/kubernetes-agent"
		else
			echo "   Check agent logs: kubectl logs -n $AGENT_NAMESPACE deployment/kubernetes-agent"
		fi
	else
		echo "   Check if agent is running on $health_url"
	fi
	exit 1
fi
echo "Response: $HEALTH_RESPONSE"
echo ""

# Test 2: Simple Analysis Request
echo "2️⃣  Testing analysis endpoint with sample pod failure..."
REQUEST_PAYLOAD=$(cat <<EOF
{
  "userId": "test-user",
  "prompt": "Analyze this pod failure and suggest a fix",
  "context": {
    "namespace": "$TEST_NAMESPACE",
    "podName": "$TEST_POD_NAME",
    "rolloutName": "quarkus-demo",
    "canaryVersion": "v2.1.0",
    "stableVersion": "v2.0.0",
    "failureReason": "CrashLoopBackOff",
    "logs": "Error: Cannot connect to database at localhost:5432\\nConnection refused\\nExiting...",
    "events": [
      {
        "type": "Warning",
        "reason": "BackOff",
        "message": "Back-off restarting failed container"
      }
    ],
    "repoUrl": "https://github.com/$GITHUB_REPO",
    "baseBranch": "main"
  }
}
EOF
)

echo "Sending request..."
if [[ "$MODE" == "local" ]]; then
	if ! ANALYSIS_RESPONSE=$(curl -sSf -X POST \
		-H 'Content-Type: application/json' \
		-d "$REQUEST_PAYLOAD" \
		"$BASE_URL/a2a/analyze"); then
		echo "❌ Request failed. Agent may be unresponsive or rate limited."
		echo "   Check if agent is running on $LOCAL_URL"
		exit 1
	fi
else
	if ! ANALYSIS_RESPONSE=$(_kubectl exec deployment/kubernetes-agent -- sh -c "curl -sSf -X POST \
		-H 'Content-Type: application/json' \
		-d '$REQUEST_PAYLOAD' \
		$BASE_URL/a2a/analyze"); then
		echo "❌ Request failed. Agent may be unresponsive or rate limited."
		if [[ -n "$CONTEXT" ]]; then
			echo "   Check agent logs: kubectl logs --context $CONTEXT -n $AGENT_NAMESPACE deployment/kubernetes-agent"
		else
			echo "   Check agent logs: kubectl logs -n $AGENT_NAMESPACE deployment/kubernetes-agent"
		fi
		exit 1
	fi
fi

echo "Response:"
echo "$ANALYSIS_RESPONSE" | jq . 2>/dev/null || echo "$ANALYSIS_RESPONSE"
echo ""

# Test 3: Check for errors in logs
echo "3️⃣  Checking agent logs for errors..."
if [[ "$MODE" == "k8s" ]]; then
	ERROR_COUNT=$(_kubectl logs deployment/kubernetes-agent | grep -c " ERROR " || true)
	if [ "$ERROR_COUNT" -eq "0" ]; then
		echo "✅ No ERROR lines found in agent logs"
	else
		echo "⚠️  Found $ERROR_COUNT ERROR lines in logs:"
		_kubectl logs deployment/kubernetes-agent | grep " ERROR " | tail -5
	fi
else
	echo "ℹ️  Log checking skipped in local mode"
	echo "   View logs in your local terminal where the agent is running"
fi
echo ""
echo "✅ Test completed!"
echo ""
