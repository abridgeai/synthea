package org.mitre.synthea.export.llm;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.mitre.synthea.helpers.Config;

/**
 * Shared, bounded thread pool that caps the total number of in-flight LLM HTTP requests for the
 * whole run, independently of how many patients Synthea generates in parallel.
 *
 * <p>Patient generation already runs one patient per thread on {@link
 * org.mitre.synthea.engine.Generator}'s pool (sized to the CPU count), and each patient fans its
 * encounters out to this pool via {@link LlmEncounterExporter}. Because LLM calls are I/O-bound
 * rather than CPU-bound, the useful level of request concurrency is unrelated to the core count,
 * and left unbounded it would overrun the provider's rate limit. This pool is the single knob that
 * bounds it: no more than {@link #concurrency()} requests are in flight at once, across every
 * patient and both the note and transcript exporters, which share this one pool.
 *
 * <p>Concurrency is configured with {@code exporter.llm.max_concurrent_requests} (default 50). A
 * value of {@code 1} disables encounter-level fan-out entirely ({@link #isParallel()} returns
 * false) so callers run serially with no executor overhead, reproducing the original behavior.
 *
 * <p>Threads are daemons, so a forgotten shutdown never blocks JVM exit; {@link #shutdown()} is
 * nonetheless invoked once per run by {@link LlmStatsExporter} to release the pool between runs in
 * the same JVM (e.g. tests).
 */
public final class LlmExecutor {

  /** Config key for the maximum number of concurrent LLM requests across the whole run. */
  static final String CONCURRENCY_KEY = "exporter.llm.max_concurrent_requests";

  private static final int DEFAULT_CONCURRENCY = 50;

  private static volatile ExecutorService pool;

  private LlmExecutor() {
  }

  /**
   * The configured global concurrency cap, floored at 1.
   *
   * @return the maximum number of simultaneous LLM requests
   */
  static int concurrency() {
    return Math.max(1, Config.getAsInteger(CONCURRENCY_KEY, DEFAULT_CONCURRENCY));
  }

  /**
   * Whether encounter-level fan-out should be used. When the cap is 1, callers should generate
   * serially rather than pay executor and future-tracking overhead.
   *
   * @return true if the configured concurrency is greater than 1
   */
  public static boolean isParallel() {
    return concurrency() > 1;
  }

  /**
   * The shared bounded pool, created lazily and sized to {@link #concurrency()} on first use.
   *
   * @return the shared executor
   */
  static ExecutorService pool() {
    ExecutorService p = pool;
    if (p == null) {
      synchronized (LlmExecutor.class) {
        p = pool;
        if (p == null) {
          p = Executors.newFixedThreadPool(concurrency(), daemonFactory());
          pool = p;
        }
      }
    }
    return p;
  }

  /**
   * Shut down the shared pool if one was created. Safe to call when none exists, and safe to call
   * more than once; a subsequent {@link #pool()} call transparently recreates it.
   */
  public static synchronized void shutdown() {
    if (pool != null) {
      pool.shutdownNow();
      pool = null;
    }
  }

  private static ThreadFactory daemonFactory() {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "llm-export-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
