package com.google.adk.tools;

import com.google.adk.models.LlmRequest;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GoogleMaps;
import com.google.genai.types.Tool;
import io.reactivex.rxjava3.core.Completable;
import java.util.List;

/**
 * A built-in tool that is automatically invoked by Gemini 2 models to retrieve search results from
 * Google Maps.
 *
 * <p>This tool operates internally within the model and does not require or perform local code
 * execution.
 *
 * <p>Usage example in an LlmAgent:
 *
 * <pre>{@code
 * LlmAgent agent = LlmAgent.builder()
 *     .addTool(new GoogleMapsTool())
 *     .build();
 * }</pre>
 *
 * <p>You can pass specific latitude and longitude coordinates, via the <code>
 * generateContentConfig()</code> method of the `LlmAgent` build:
 *
 * <pre>
 * LlmAgent agent = LlmAgent.builder()
 *   .addTool(new GoogleMapsTool())
 *   .generateContentConfig(GenerateContentConfig.builder()
 *     .toolConfig(ToolConfig.builder()
 *         .retrievalConfig(RetrievalConfig.builder()
 *             .latLng(LatLng.builder()
 *                 .latitude(latitude)
 *                 .longitude(longitude)
 *                 .build())
 *             .build())
 *         .build())
 *     .build())
 *   .build();
 * </pre>
 */
public class GoogleMapsTool extends BaseTool {
  public static final GoogleMapsTool INSTANCE = new GoogleMapsTool();

  public GoogleMapsTool() {
    super("google_maps", "google_maps");
  }

  @Override
  public Completable processLlmRequest(
      LlmRequest.Builder llmRequestBuilder, ToolContext toolContext) {

    GenerateContentConfig.Builder configBuilder =
        llmRequestBuilder
            .build()
            .config()
            .map(GenerateContentConfig::toBuilder)
            .orElse(GenerateContentConfig.builder());

    List<Tool> existingTools = configBuilder.build().tools().orElse(ImmutableList.of());
    ImmutableList.Builder<Tool> updatedToolsBuilder = ImmutableList.builder();
    updatedToolsBuilder.addAll(existingTools);

    String model = llmRequestBuilder.build().model().orElse(null);
    if (model != null && !model.startsWith("gemini-1")) {
      updatedToolsBuilder.add(Tool.builder().googleMaps(GoogleMaps.builder().build()).build());
      configBuilder.tools(updatedToolsBuilder.build());
    } else {
      return Completable.error(
          new IllegalArgumentException("Google Maps tool is not supported for model " + model));
    }

    llmRequestBuilder.config(configBuilder.build());
    return Completable.complete();
  }
}
