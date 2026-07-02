package org.mitre.synthea.export.llm;

import org.mitre.synthea.engine.Generator;
import org.mitre.synthea.export.Exporter.ExporterRuntimeOptions;
import org.mitre.synthea.export.PostCompletionExporter;

/**
 * Reports LLM API usage once after a generation run completes: the number of API calls made and,
 * when the provider returns a {@code usage} object, the prompt/completion/total token counts.
 * Cache hits are not counted as API calls. Nothing is printed if no API calls were made (i.e. the
 * LLM exporters were disabled or every response was served from cache).
 */
public class LlmStatsExporter implements PostCompletionExporter {

  @Override
  public void export(Generator generator, ExporterRuntimeOptions options) {
    long apiCalls = LlmClient.getApiCallCount();
    if (apiCalls == 0) {
      return;
    }
    long promptTokens = LlmClient.getPromptTokens();
    long completionTokens = LlmClient.getCompletionTokens();
    long totalTokens = LlmClient.getTotalTokens();

    StringBuilder sb = new StringBuilder();
    sb.append("\n=== LLM export usage ===\n");
    sb.append("API calls: ").append(apiCalls).append("\n");
    if (totalTokens > 0 || promptTokens > 0 || completionTokens > 0) {
      sb.append("Tokens: ").append(totalTokens).append(" total")
          .append(" (").append(promptTokens).append(" prompt, ")
          .append(completionTokens).append(" completion)\n");
    } else {
      sb.append("Tokens: not reported by the provider\n");
    }
    System.out.print(sb);

    // Reset so a subsequent run in the same JVM starts from zero.
    LlmClient.resetStats();
  }
}
