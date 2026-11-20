package com.grocery.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Phase 11: the Shopping and Admin BI assistants and human approval of sensitive actions
 * (docs/api/phase-11-agentic-ai-and-approvals.md, ADR-0008).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiOrchestratorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiOrchestratorServiceApplication.class, args);
    }
}
