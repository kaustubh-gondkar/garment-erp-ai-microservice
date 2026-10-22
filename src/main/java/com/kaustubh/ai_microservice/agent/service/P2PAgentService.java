package com.kaustubh.ai_microservice.agent.service;

import com.kaustubh.ai_microservice.tools.ComplianceRagTools;
import com.kaustubh.ai_microservice.tools.ProcurementTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

@Service
public class P2PAgentService {

    private final ChatClient chatClient;
    private final ProcurementTools procurementTools;
    private final ComplianceRagTools complianceRagTools;

    public P2PAgentService(
            ChatClient.Builder chatClientBuilder,
            ProcurementTools procurementTools,
            ComplianceRagTools complianceRagTools,
            ChatMemory chatMemory, // Inject today's JdbcSlidingWindowChatMemory
            org.springframework.ai.chat.client.advisor.api.CallAdvisor loopCircuitBreakerAdvisor // Optional Day 3 advisor
    ) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        You are an Enterprise Procure-to-Pay (P2P) Supply Chain Risk Agent.
                        When provided with verified graph state and user instructions:
                        1. Respect all pre-computed inventory, in-flight, and compliance checks.
                        2. Execute tool calls (`createPurchaseOrder`) exactly as instructed.
                        3. Report the PO ID, total cost in INR, and approval status clearly.
                        """)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                        // loopCircuitBreakerAdvisor // Uncomment if you want circuit breaking globally here
                )
                .defaultTools(procurementTools, complianceRagTools)
                .build();
        this.procurementTools = procurementTools;
        this.complianceRagTools = complianceRagTools;
    }

    // Original method for simple runs
    public String executeProcurementRun(String userPrompt) {
        return chatClient.prompt()
                .user(userPrompt)
                .call()
                .content();
    }

    // NEW: Reusable method for P2pGraphOrchestrator that supports conversation memory & structured context
    public String executeWithStructuredContext(String structuredContext, String conversationId) {
        return chatClient.prompt()
                .user(structuredContext)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }
}