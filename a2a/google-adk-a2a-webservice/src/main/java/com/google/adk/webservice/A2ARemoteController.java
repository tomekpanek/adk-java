package com.google.adk.webservice;

import io.a2a.spec.AgentCard;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST controller exposing an A2A-compliant JSON-RPC endpoint backed by a local ADK runner. */
@RestController
@RequestMapping("/a2a/remote")
public class A2ARemoteController {

  private static final Logger logger = LoggerFactory.getLogger(A2ARemoteController.class);

  private final A2ARemoteService service;

  public A2ARemoteController(A2ARemoteService service) {
    this.service = service;
  }

  @PostMapping(
      path = "/v1/message:send",
      consumes = "application/json",
      produces = "application/json")
  public SendMessageResponse sendMessage(@RequestBody SendMessageRequest request) {
    logger.debug("Received remote A2A request: {}", request);
    SendMessageResponse response = service.handle(request);
    logger.debug("Responding with remote A2A payload: {}", response);
    return response;
  }

  /** Get agent card information */
  @GetMapping(path = "/v1/.well-known/agent-card.json", produces = "application/json")
  public ResponseEntity<AgentCard> getAgentCard() {
    return ResponseEntity.ok(service.getAgentCard());
  }
}
