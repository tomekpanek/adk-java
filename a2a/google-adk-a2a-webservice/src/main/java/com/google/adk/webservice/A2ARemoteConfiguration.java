package com.google.adk.webservice;

import com.google.adk.a2a.A2ASendMessageExecutor;
import com.google.adk.agents.BaseAgent;
import com.google.adk.runner.Runner;
import io.a2a.spec.AgentCard;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the transport-only A2A webservice stack.
 *
 * <p>Importers must supply a {@link BaseAgent} bean. The agent remains opaque to this module so the
 * transport can be reused across applications.
 *
 * <p>TODO:
 *
 * <ul>
 *   <li>Expose discovery endpoints (agent card / extended card) so clients can fetch metadata
 *       directly.
 *   <li>Add optional remote-proxy wiring for cases where no local agent bean is available.
 * </ul>
 */
@Configuration
@ComponentScan(basePackages = "com.google.adk.webservice")
public class A2ARemoteConfiguration {

  private static final Logger logger = LoggerFactory.getLogger(A2ARemoteConfiguration.class);
  private static final String DEFAULT_APP_NAME = "a2a-remote-service";
  private static final long DEFAULT_TIMEOUT_SECONDS = 15L;

  @Bean
  public A2ASendMessageExecutor a2aSendMessageExecutor(
      @Autowired(required = false) BaseAgent agent,
      @Autowired(required = false) Runner runner,
      @Value("${a2a.remote.appName:" + DEFAULT_APP_NAME + "}") String appName,
      @Value("${a2a.remote.timeoutSeconds:" + DEFAULT_TIMEOUT_SECONDS + "}") long timeoutSeconds,
      AgentCard agentCard) {
    logger.info(
        "Initializing A2A send message executor for appName {} with timeout {}s",
        appName,
        timeoutSeconds);
    if (agent != null) {
      return new A2ASendMessageExecutor(
          agent, appName, Duration.ofSeconds(timeoutSeconds), agentCard);
    } else if (runner != null) {
      return new A2ASendMessageExecutor(
          runner, appName, Duration.ofSeconds(timeoutSeconds), agentCard);
    }
    throw new IllegalStateException("Neither BaseAgent nor Runner is available!");
  }
}
