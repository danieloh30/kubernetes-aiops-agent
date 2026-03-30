package dev.danieloh.rollout.agent.utils;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;

import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Rate limiter for OpenAI API calls to prevent TPM (Tokens Per Minute) limit errors.
 * Tracks token usage over a sliding window and enforces delays when approaching limits.
 */
@ApplicationScoped
public class RateLimiter {
    
    private static final int TPM_LIMIT = 28000; // Conservative limit (below 30K)
    private static final Duration WINDOW_DURATION = Duration.ofMinutes(1);
    private static final Duration MIN_DELAY_BETWEEN_CALLS = Duration.ofSeconds(3);
    
    private final ConcurrentLinkedQueue<TokenUsage> usageHistory = new ConcurrentLinkedQueue<>();
    private final ReentrantLock lock = new ReentrantLock();
    private Instant lastCallTime = Instant.now().minus(MIN_DELAY_BETWEEN_CALLS);
    
    /**
     * Record of token usage at a specific time
     */
    private record TokenUsage(Instant timestamp, int tokens) {}
    
    /**
     * Wait if necessary to stay within rate limits before making an API call.
     * This method blocks until it's safe to proceed.
     * 
     * @param estimatedTokens Estimated tokens for the upcoming request
     */
    public void acquirePermit(int estimatedTokens) {
        lock.lock();
        try {
            // Clean up old entries outside the window
            cleanupOldEntries();
            
            // Calculate current usage in the window
            int currentUsage = calculateCurrentUsage();
            
            // Check if we need to wait for minimum delay between calls
            Duration timeSinceLastCall = Duration.between(lastCallTime, Instant.now());
            if (timeSinceLastCall.compareTo(MIN_DELAY_BETWEEN_CALLS) < 0) {
                Duration waitTime = MIN_DELAY_BETWEEN_CALLS.minus(timeSinceLastCall);
                Log.info(MessageFormat.format("Rate limiting: waiting {0}ms for minimum delay between calls", 
                        waitTime.toMillis()));
                sleep(waitTime);
            }
            
            // Check if adding this request would exceed the limit
            if (currentUsage + estimatedTokens > TPM_LIMIT) {
                // Calculate how long to wait for the oldest entry to expire
                TokenUsage oldest = usageHistory.peek();
                if (oldest != null) {
                    Instant windowStart = Instant.now().minus(WINDOW_DURATION);
                    if (oldest.timestamp.isBefore(windowStart)) {
                        // Oldest entry is already expired, clean up and retry
                        cleanupOldEntries();
                        currentUsage = calculateCurrentUsage();
                    } else {
                        // Wait for oldest entry to expire
                        Duration waitTime = Duration.between(Instant.now(), 
                                oldest.timestamp.plus(WINDOW_DURATION)).plusSeconds(1);
                        Log.warn(MessageFormat.format(
                                "Rate limit approaching: current={0}, requested={1}, limit={2}. Waiting {3}s",
                                currentUsage, estimatedTokens, TPM_LIMIT, waitTime.getSeconds()));
                        sleep(waitTime);
                        cleanupOldEntries();
                    }
                }
            }
            
            // Record this usage
            usageHistory.offer(new TokenUsage(Instant.now(), estimatedTokens));
            lastCallTime = Instant.now();
            
            Log.debug(MessageFormat.format("Rate limiter: acquired permit for {0} tokens (current usage: {1}/{2})",
                    estimatedTokens, currentUsage + estimatedTokens, TPM_LIMIT));
            
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Record actual token usage after an API call completes.
     * This helps improve accuracy for future rate limiting decisions.
     * 
     * @param actualTokens Actual tokens used by the completed request
     */
    public void recordActualUsage(int actualTokens) {
        lock.lock();
        try {
            // Remove the last estimated entry and add actual usage
            TokenUsage lastEntry = usageHistory.poll();
            if (lastEntry != null) {
                usageHistory.offer(new TokenUsage(lastEntry.timestamp, actualTokens));
                Log.debug(MessageFormat.format("Rate limiter: updated usage from {0} to {1} tokens",
                        lastEntry.tokens, actualTokens));
            }
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Get current token usage in the sliding window
     */
    public int getCurrentUsage() {
        lock.lock();
        try {
            cleanupOldEntries();
            return calculateCurrentUsage();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * Remove entries older than the window duration
     */
    private void cleanupOldEntries() {
        Instant windowStart = Instant.now().minus(WINDOW_DURATION);
        while (!usageHistory.isEmpty()) {
            TokenUsage oldest = usageHistory.peek();
            if (oldest != null && oldest.timestamp.isBefore(windowStart)) {
                usageHistory.poll();
                Log.debug(MessageFormat.format("Rate limiter: removed expired entry ({0} tokens)",
                        oldest.tokens));
            } else {
                break;
            }
        }
    }
    
    /**
     * Calculate total token usage in the current window
     */
    private int calculateCurrentUsage() {
        return usageHistory.stream()
                .mapToInt(TokenUsage::tokens)
                .sum();
    }
    
    /**
     * Sleep for the specified duration, handling interruptions
     */
    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.warn("Rate limiter sleep interrupted", e);
        }
    }
    
    /**
     * Reset the rate limiter (useful for testing)
     */
    public void reset() {
        lock.lock();
        try {
            usageHistory.clear();
            lastCallTime = Instant.now().minus(MIN_DELAY_BETWEEN_CALLS);
            Log.debug("Rate limiter reset");
        } finally {
            lock.unlock();
        }
    }
}

// Made with Bob
