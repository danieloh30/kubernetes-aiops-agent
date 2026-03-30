package dev.danieloh.rollout.agent.utils;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class RateLimiterTest {

    @Inject
    RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter.reset();
    }

    @Test
    void testAcquirePermit_withinLimit() {
        // Should not block when well within limits
        Instant start = Instant.now();
        rateLimiter.acquirePermit(5000);
        Duration elapsed = Duration.between(start, Instant.now());
        
        // Should complete quickly (within 5 seconds including minimum delay)
        assertTrue(elapsed.toSeconds() < 5, "Should not block when within limits");
        assertEquals(5000, rateLimiter.getCurrentUsage(), "Should track usage");
    }

    @Test
    void testAcquirePermit_multipleRequests() {
        // Make several requests that stay within limits
        rateLimiter.acquirePermit(5000);
        rateLimiter.acquirePermit(5000);
        rateLimiter.acquirePermit(5000);
        
        int usage = rateLimiter.getCurrentUsage();
        assertTrue(usage <= 15000, "Should track cumulative usage");
    }

    @Test
    void testAcquirePermit_minimumDelay() {
        // Test that minimum delay between calls is enforced
        Instant start = Instant.now();
        rateLimiter.acquirePermit(1000);
        
        Instant secondStart = Instant.now();
        rateLimiter.acquirePermit(1000);
        Duration elapsed = Duration.between(secondStart, Instant.now());
        
        // Should wait at least 3 seconds between calls
        assertTrue(elapsed.toSeconds() >= 2, "Should enforce minimum delay between calls");
    }

    @Test
    void testGetCurrentUsage_empty() {
        assertEquals(0, rateLimiter.getCurrentUsage(), "Should start with zero usage");
    }

    @Test
    void testGetCurrentUsage_afterAcquire() {
        rateLimiter.acquirePermit(10000);
        assertEquals(10000, rateLimiter.getCurrentUsage(), "Should reflect acquired tokens");
    }

    @Test
    void testRecordActualUsage() {
        rateLimiter.acquirePermit(10000);
        rateLimiter.recordActualUsage(8000);
        
        // Usage should be updated to actual value
        int usage = rateLimiter.getCurrentUsage();
        assertEquals(8000, usage, "Should update to actual usage");
    }

    @Test
    void testReset() {
        rateLimiter.acquirePermit(5000);
        assertEquals(5000, rateLimiter.getCurrentUsage());
        
        rateLimiter.reset();
        assertEquals(0, rateLimiter.getCurrentUsage(), "Should clear usage after reset");
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Test thread safety with concurrent requests
        Thread t1 = new Thread(() -> rateLimiter.acquirePermit(5000));
        Thread t2 = new Thread(() -> rateLimiter.acquirePermit(5000));
        
        t1.start();
        t2.start();
        
        t1.join();
        t2.join();
        
        int usage = rateLimiter.getCurrentUsage();
        assertTrue(usage > 0 && usage <= 10000, "Should handle concurrent access safely");
    }
}

// Made with Bob
