/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.adk.flows.llmflows;

import com.google.adk.Telemetry;
import com.google.adk.agents.ActiveStreamingTool;
import com.google.adk.agents.Callbacks.AfterToolCallback;
import com.google.adk.agents.Callbacks.BeforeToolCallback;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.FunctionTool;
import com.google.adk.tools.ToolContext;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Utility class for handling function calls. */
public final class Functions {

  private static final String AF_FUNCTION_CALL_ID_PREFIX = "adk-";
  private static final Logger logger = LoggerFactory.getLogger(Functions.class);

  /** Generates a unique ID for a function call. */
  public static String generateClientFunctionCallId() {
    return AF_FUNCTION_CALL_ID_PREFIX + UUID.randomUUID();
  }

  /**
   * Populates missing function call IDs in the provided event's content.
   *
   * <p>If the event contains function calls without an ID, this method generates a unique
   * client-side ID for each and updates the event content.
   *
   * @param modelResponseEvent The event potentially containing function calls.
   */
  public static void populateClientFunctionCallId(Event modelResponseEvent) {
    Optional<Content> originalContentOptional = modelResponseEvent.content();
    if (originalContentOptional.isEmpty()) {
      return;
    }
    Content originalContent = originalContentOptional.get();
    List<Part> originalParts = originalContent.parts().orElse(ImmutableList.of());
    if (originalParts.stream().noneMatch(part -> part.functionCall().isPresent())) {
      return; // No function calls to process
    }

    List<Part> newParts = new ArrayList<>();
    boolean modified = false;
    for (Part part : originalParts) {
      if (part.functionCall().isPresent()) {
        FunctionCall functionCall = part.functionCall().get();
        if (functionCall.id().isEmpty() || functionCall.id().get().isEmpty()) {
          FunctionCall updatedFunctionCall =
              functionCall.toBuilder().id(generateClientFunctionCallId()).build();
          newParts.add(Part.builder().functionCall(updatedFunctionCall).build());
          modified = true;
        } else {
          newParts.add(part); // Keep original part if ID exists
        }
      } else {
        newParts.add(part); // Keep non-function call parts
      }
    }

    if (modified) {
      String role =
          originalContent
              .role()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Content role is missing in event: " + modelResponseEvent.id()));
      Content newContent = Content.builder().role(role).parts(newParts).build();
      modelResponseEvent.setContent(Optional.of(newContent));
    }
  }

  // TODO - b/413761119 add the remaining methods for function call id.

  /** Handles standard, non-streaming function calls. */
  public static Maybe<Event> handleFunctionCalls(
      InvocationContext invocationContext, Event functionCallEvent, Map<String, BaseTool> tools) {
    ImmutableList<FunctionCall> functionCalls = functionCallEvent.functionCalls();

    List<Maybe<Event>> functionResponseEvents = new ArrayList<>();

    for (FunctionCall functionCall : functionCalls) {
      if (!tools.containsKey(functionCall.name().get())) {
        throw new VerifyException("Tool not found: " + functionCall.name().get());
      }
      BaseTool tool = tools.get(functionCall.name().get());
      ToolContext toolContext =
          ToolContext.builder(invocationContext)
              .functionCallId(functionCall.id().orElse(""))
              .build();

      Map<String, Object> functionArgs = functionCall.args().orElse(new HashMap<>());

      Maybe<Map<String, Object>> maybeFunctionResult =
          maybeInvokeBeforeToolCall(invocationContext, tool, functionArgs, toolContext)
              .switchIfEmpty(Maybe.defer(() -> callTool(tool, functionArgs, toolContext)));

      Maybe<Event> maybeFunctionResponseEvent =
          maybeFunctionResult
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty())
              .onErrorResumeNext(
                  t ->
                      invocationContext
                          .pluginManager()
                          .runOnToolErrorCallback(tool, functionArgs, toolContext, t)
                          .map(Optional::of)
                          .switchIfEmpty(Single.error(t)))
              .flatMapMaybe(
                  optionalInitialResult -> {
                    Map<String, Object> initialFunctionResult = optionalInitialResult.orElse(null);

                    Maybe<Map<String, Object>> afterToolResultMaybe =
                        maybeInvokeAfterToolCall(
                            invocationContext,
                            tool,
                            functionArgs,
                            toolContext,
                            initialFunctionResult);

                    return afterToolResultMaybe
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.ofNullable(initialFunctionResult))
                        .flatMapMaybe(
                            finalOptionalResult -> {
                              Map<String, Object> finalFunctionResult =
                                  finalOptionalResult.orElse(null);
                              if (tool.longRunning() && finalFunctionResult == null) {
                                return Maybe.empty();
                              }
                              Event functionResponseEvent =
                                  buildResponseEvent(
                                      tool, finalFunctionResult, toolContext, invocationContext);
                              return Maybe.just(functionResponseEvent);
                            });
                  });

      functionResponseEvents.add(maybeFunctionResponseEvent);
    }

    return Maybe.merge(functionResponseEvents)
        .toList()
        .flatMapMaybe(
            events -> {
              if (events.isEmpty()) {
                return Maybe.empty();
              }
              Event mergedEvent = Functions.mergeParallelFunctionResponseEvents(events);
              if (mergedEvent == null) {
                return Maybe.empty();
              }

              if (events.size() > 1) {
                Tracer tracer = Telemetry.getTracer();
                Span mergedSpan =
                    tracer.spanBuilder("tool_response").setParent(Context.current()).startSpan();
                try (Scope scope = mergedSpan.makeCurrent()) {
                  Telemetry.traceToolResponse(invocationContext, mergedEvent.id(), mergedEvent);
                } finally {
                  mergedSpan.end();
                }
              }
              return Maybe.just(mergedEvent);
            });
  }

  /**
   * Handles function calls in a live/streaming context, supporting background execution and stream
   * termination.
   */
  public static Maybe<Event> handleFunctionCallsLive(
      InvocationContext invocationContext, Event functionCallEvent, Map<String, BaseTool> tools) {
    ImmutableList<FunctionCall> functionCalls = functionCallEvent.functionCalls();
    List<Maybe<Event>> responseEvents = new ArrayList<>();

    for (FunctionCall functionCall : functionCalls) {
      if (!tools.containsKey(functionCall.name().get())) {
        throw new VerifyException("Tool not found: " + functionCall.name().get());
      }
      BaseTool tool = tools.get(functionCall.name().get());
      ToolContext toolContext =
          ToolContext.builder(invocationContext)
              .functionCallId(functionCall.id().orElse(""))
              .build();
      Map<String, Object> functionArgs = functionCall.args().orElse(new HashMap<>());

      Maybe<Map<String, Object>> maybeFunctionResult =
          maybeInvokeBeforeToolCall(invocationContext, tool, functionArgs, toolContext)
              .switchIfEmpty(
                  Maybe.defer(
                      () ->
                          processFunctionLive(
                              invocationContext, tool, toolContext, functionCall, functionArgs)));

      Maybe<Event> maybeFunctionResponseEvent =
          maybeFunctionResult
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty())
              .onErrorResumeNext(
                  t ->
                      invocationContext
                          .pluginManager()
                          .runOnToolErrorCallback(tool, functionArgs, toolContext, t)
                          .map(Optional::ofNullable)
                          .switchIfEmpty(Single.error(t)))
              .flatMapMaybe(
                  optionalInitialResult -> {
                    Map<String, Object> initialFunctionResult = optionalInitialResult.orElse(null);

                    Maybe<Map<String, Object>> afterToolResultMaybe =
                        maybeInvokeAfterToolCall(
                            invocationContext,
                            tool,
                            functionArgs,
                            toolContext,
                            initialFunctionResult);

                    return afterToolResultMaybe
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.ofNullable(initialFunctionResult))
                        .flatMapMaybe(
                            finalOptionalResult -> {
                              Map<String, Object> finalFunctionResult =
                                  finalOptionalResult.orElse(null);
                              if (tool.longRunning() && finalFunctionResult == null) {
                                return Maybe.empty();
                              }
                              Event functionResponseEvent =
                                  buildResponseEvent(
                                      tool, finalFunctionResult, toolContext, invocationContext);
                              return Maybe.just(functionResponseEvent);
                            });
                  });
      responseEvents.add(maybeFunctionResponseEvent);
    }

    return Maybe.merge(responseEvents)
        .toList()
        .flatMapMaybe(
            events -> {
              if (events.isEmpty()) {
                return Maybe.empty();
              }
              return Maybe.just(Functions.mergeParallelFunctionResponseEvents(events));
            });
  }

  /**
   * Processes a single function call in a live context. Manages starting, stopping, and running
   * tools.
   */
  private static Maybe<Map<String, Object>> processFunctionLive(
      InvocationContext invocationContext,
      BaseTool tool,
      ToolContext toolContext,
      FunctionCall functionCall,
      Map<String, Object> args) {
    // Case 1: Handle a call to stopStreaming
    if (functionCall.name().get().equals("stopStreaming") && args.containsKey("functionName")) {
      String functionNameToStop = (String) args.get("functionName");
      ActiveStreamingTool activeTool =
          invocationContext.activeStreamingTools().get(functionNameToStop);
      if (activeTool != null) {
        // Dispose the running task if it exists and is not disposed
        if (activeTool.task() != null && !activeTool.task().isDisposed()) {
          activeTool.task().dispose();
        }
        // Close the associated output stream if it exists
        if (activeTool.stream() != null) {
          activeTool.stream().close();
        }
        invocationContext.activeStreamingTools().remove(functionNameToStop);
        logger.info("Successfully stopped streaming function {}", functionNameToStop);
        return Maybe.just(
            ImmutableMap.of(
                "status", "Successfully stopped streaming function " + functionNameToStop));
      } else {
        logger.warn("No active streaming function named {} found to stop", functionNameToStop);
        return Maybe.just(
            ImmutableMap.of("status", "No active streaming function named " + functionNameToStop));
      }
    }

    // Case 2: Handle a streaming-capable tool (FunctionTool with Flowable return type)
    if (tool instanceof FunctionTool functionTool) {
      if (functionTool.isStreaming()) {
        try {
          Flowable<Map<String, Object>> toolOutputStream =
              functionTool.callLive(args, toolContext, invocationContext);

          // Subscribe to the tool's output to process results in the background.
          Disposable subscription =
              toolOutputStream.subscribe(
                  result -> {
                    String resultText = "Function " + tool.name() + " returned: " + result;
                    Content updateContent =
                        Content.builder().role("user").parts(Part.fromText(resultText)).build();
                    invocationContext.liveRequestQueue().get().content(updateContent);
                  },
                  error -> logger.error("Error in streaming tool " + tool.name(), error.getCause()),
                  () -> {
                    logger.info("Streaming tool {} completed.", tool.name());
                    invocationContext.activeStreamingTools().remove(tool.name());
                  });

          ActiveStreamingTool activeTool =
              invocationContext
                  .activeStreamingTools()
                  .getOrDefault(tool.name(), new ActiveStreamingTool(subscription));
          activeTool.task(subscription);
          invocationContext.activeStreamingTools().put(tool.name(), activeTool);

          return Maybe.just(
              ImmutableMap.of(
                  "status", "The function is running asynchronously and the results are pending."));

        } catch (Exception e) {
          logger.error("Failed to start streaming tool: " + tool.name(), e);
          return Maybe.error(e);
        }
      }
    }

    // Case 3: Fallback for regular, non-streaming tools
    return callTool(tool, args, toolContext);
  }

  public static Set<String> getLongRunningFunctionCalls(
      List<FunctionCall> functionCalls, Map<String, BaseTool> tools) {
    Set<String> longRunningFunctionCalls = new HashSet<>();
    for (FunctionCall functionCall : functionCalls) {
      if (!tools.containsKey(functionCall.name().get())) {
        continue;
      }
      BaseTool tool = tools.get(functionCall.name().get());
      if (tool.longRunning()) {
        longRunningFunctionCalls.add(functionCall.id().orElse(""));
      }
    }
    return longRunningFunctionCalls;
  }

  private static @Nullable Event mergeParallelFunctionResponseEvents(
      List<Event> functionResponseEvents) {
    if (functionResponseEvents.isEmpty()) {
      return null;
    }
    if (functionResponseEvents.size() == 1) {
      return functionResponseEvents.get(0);
    }
    // Use the first event as the base for common attributes
    Event baseEvent = functionResponseEvents.get(0);

    List<Part> mergedParts = new ArrayList<>();
    for (Event event : functionResponseEvents) {
      event.content().flatMap(Content::parts).ifPresent(mergedParts::addAll);
    }

    // Merge actions from all events
    // TODO: validate that pending actions are not cleared away
    EventActions.Builder mergedActionsBuilder = EventActions.builder();
    for (Event event : functionResponseEvents) {
      mergedActionsBuilder.merge(event.actions());
    }

    return Event.builder()
        .id(Event.generateEventId())
        .invocationId(baseEvent.invocationId())
        .author(baseEvent.author())
        .branch(baseEvent.branch())
        .content(Optional.of(Content.builder().role("user").parts(mergedParts).build()))
        .actions(mergedActionsBuilder.build())
        .timestamp(baseEvent.timestamp())
        .build();
  }

  private static Maybe<Map<String, Object>> maybeInvokeBeforeToolCall(
      InvocationContext invocationContext,
      BaseTool tool,
      Map<String, Object> functionArgs,
      ToolContext toolContext) {
    if (invocationContext.agent() instanceof LlmAgent) {
      LlmAgent agent = (LlmAgent) invocationContext.agent();

      Maybe<Map<String, Object>> pluginResult =
          invocationContext.pluginManager().runBeforeToolCallback(tool, functionArgs, toolContext);

      Optional<List<? extends BeforeToolCallback>> callbacksOpt = agent.beforeToolCallback();
      if (callbacksOpt.isEmpty() || callbacksOpt.get().isEmpty()) {
        return pluginResult;
      }
      List<? extends BeforeToolCallback> callbacks = callbacksOpt.get();

      Maybe<Map<String, Object>> callbackResult =
          Maybe.defer(
              () ->
                  Flowable.fromIterable(callbacks)
                      .concatMapMaybe(
                          callback ->
                              callback.call(invocationContext, tool, functionArgs, toolContext))
                      .firstElement());

      return pluginResult.switchIfEmpty(callbackResult);
    }
    return Maybe.empty();
  }

  private static Maybe<Map<String, Object>> maybeInvokeAfterToolCall(
      InvocationContext invocationContext,
      BaseTool tool,
      Map<String, Object> functionArgs,
      ToolContext toolContext,
      Map<String, Object> functionResult) {
    if (invocationContext.agent() instanceof LlmAgent) {
      LlmAgent agent = (LlmAgent) invocationContext.agent();

      Maybe<Map<String, Object>> pluginResult =
          invocationContext
              .pluginManager()
              .runAfterToolCallback(tool, functionArgs, toolContext, functionResult);

      Optional<List<? extends AfterToolCallback>> callbacksOpt = agent.afterToolCallback();
      if (callbacksOpt.isEmpty() || callbacksOpt.get().isEmpty()) {
        return pluginResult;
      }
      List<? extends AfterToolCallback> callbacks = callbacksOpt.get();

      Maybe<Map<String, Object>> callbackResult =
          Maybe.defer(
              () ->
                  Flowable.fromIterable(callbacks)
                      .concatMapMaybe(
                          callback ->
                              callback.call(
                                  invocationContext,
                                  tool,
                                  functionArgs,
                                  toolContext,
                                  functionResult))
                      .firstElement());

      return pluginResult.switchIfEmpty(callbackResult);
    }
    return Maybe.empty();
  }

  private static Maybe<Map<String, Object>> callTool(
      BaseTool tool, Map<String, Object> args, ToolContext toolContext) {
    Tracer tracer = Telemetry.getTracer();
    return Maybe.defer(
        () -> {
          Span span =
              tracer
                  .spanBuilder("tool_call [" + tool.name() + "]")
                  .setParent(Context.current())
                  .startSpan();
          try (Scope scope = span.makeCurrent()) {
            Telemetry.traceToolCall(args);
            return tool.runAsync(args, toolContext)
                .toMaybe()
                .doOnError(span::recordException)
                .doFinally(span::end);
          } catch (RuntimeException e) {
            span.recordException(e);
            span.end();
            return Maybe.error(new RuntimeException("Failed to call tool: " + tool.name(), e));
          }
        });
  }

  private static Event buildResponseEvent(
      BaseTool tool,
      Map<String, Object> response,
      ToolContext toolContext,
      InvocationContext invocationContext) {
    Tracer tracer = Telemetry.getTracer();
    Span span =
        tracer
            .spanBuilder("tool_response [" + tool.name() + "]")
            .setParent(Context.current())
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      // use a empty placeholder response if tool response is null.
      if (response == null) {
        response = new HashMap<>();
      }

      Part partFunctionResponse =
          Part.builder()
              .functionResponse(
                  FunctionResponse.builder()
                      .id(toolContext.functionCallId().orElse(""))
                      .name(tool.name())
                      .response(response)
                      .build())
              .build();

      Event event =
          Event.builder()
              .id(Event.generateEventId())
              .invocationId(invocationContext.invocationId())
              .author(invocationContext.agent().name())
              .branch(invocationContext.branch())
              .content(
                  Optional.of(
                      Content.builder()
                          .role("user")
                          .parts(Collections.singletonList(partFunctionResponse))
                          .build()))
              .actions(toolContext.eventActions())
              .build();
      Telemetry.traceToolResponse(invocationContext, event.id(), event);
      return event;
    } finally {
      span.end();
    }
  }

  private Functions() {}
}
