package org.mitre.synthea.export.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;
import org.mitre.synthea.helpers.Config;

public class LlmExecutorTest {

  /** Restore config and release the shared pool after each test. */
  @After
  public void tearDown() {
    Config.remove(LlmExecutor.CONCURRENCY_KEY);
    LlmExecutor.shutdown();
  }

  @Test
  public void concurrencyIsFlooredAtOne() {
    Config.set(LlmExecutor.CONCURRENCY_KEY, "0");
    assertEquals(1, LlmExecutor.concurrency());
    Config.set(LlmExecutor.CONCURRENCY_KEY, "-4");
    assertEquals(1, LlmExecutor.concurrency());
    Config.set(LlmExecutor.CONCURRENCY_KEY, "5");
    assertEquals(5, LlmExecutor.concurrency());
  }

  @Test
  public void isParallelReflectsConfig() {
    Config.set(LlmExecutor.CONCURRENCY_KEY, "1");
    assertFalse(LlmExecutor.isParallel());
    Config.set(LlmExecutor.CONCURRENCY_KEY, "2");
    assertTrue(LlmExecutor.isParallel());
  }

  @Test
  public void poolNeverExceedsConfiguredConcurrency() throws Exception {
    int limit = 3;
    Config.set(LlmExecutor.CONCURRENCY_KEY, Integer.toString(limit));

    AtomicInteger inFlight = new AtomicInteger();
    AtomicInteger maxObserved = new AtomicInteger();

    ExecutorService pool = LlmExecutor.pool();
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      futures.add(pool.submit(() -> {
        int now = inFlight.incrementAndGet();
        maxObserved.accumulateAndGet(now, Math::max);
        try {
          Thread.sleep(20);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        inFlight.decrementAndGet();
      }));
    }
    for (Future<?> future : futures) {
      future.get();
    }

    // The bounded pool must never run more tasks at once than the configured cap.
    assertTrue("max observed concurrency " + maxObserved.get() + " exceeded limit " + limit,
        maxObserved.get() <= limit);
    // With plenty of tasks and sleeps, it should actually reach the cap.
    assertEquals(limit, maxObserved.get());
  }

  @Test
  public void shutdownRecreatesPoolOnNextUse() {
    Config.set(LlmExecutor.CONCURRENCY_KEY, "2");
    ExecutorService first = LlmExecutor.pool();
    LlmExecutor.shutdown();
    assertTrue(first.isShutdown());
    ExecutorService second = LlmExecutor.pool();
    assertNotSame(first, second);
    assertFalse(second.isShutdown());
  }
}
