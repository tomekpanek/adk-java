package com.google.adk.samples.a2a_remote;

import com.google.adk.agents.BaseAgent;
import com.google.adk.samples.a2a_remote.remote_prime_agent.Agent;
import com.google.adk.webservice.A2ARemoteConfiguration;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Spring Boot entry point that wires the shared A2A webservice with the prime demo agent. */
@SpringBootApplication
@Import(A2ARemoteConfiguration.class)
public class RemoteA2AApplication {

  public static void main(String[] args) {
    SpringApplication.run(RemoteA2AApplication.class, args);
  }

  @Bean
  public BaseAgent primeAgent() {
    return Agent.ROOT_AGENT;
  }

  @Bean
  public AgentCard agentCard() {
    return new AgentCard.Builder()
        .name("check_prime_agent")
        .capabilities(new AgentCapabilities(false, false, false, List.of()))
        .description(
            "An agent specialized in checking whether numbers are prime. It can efficiently determine the primality of individual numbers or lists of numbers.")
        .url("http://localhost:8080/a2a/remote/v1/message:send")
        .version("1.0.0")
        .defaultInputModes(List.of("text/plain"))
        .defaultOutputModes(List.of("application/json"))
        .skills(
            List.of(
                new AgentSkill(
                    "prime_checking",
                    "Prime Number Checking",
                    "Check if numbers in a list are prime using efficient mathematical algorithms",
                    List.of("mathematical", "computation", "prime", "numbers"),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of())))
        .security(List.of())
        .build();
  }
}
