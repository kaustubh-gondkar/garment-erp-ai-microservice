package com.kaustubh.ai_microservice.agent.service;


import com.kaustubh.ai_microservice.tools.ProcurementTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.stereotype.Service;

@Service
public class P2PAgentService {

    private final ChatClient chatClient;
    private final ProcurementTools procurementTools;

    public P2PAgentService(ChatClient.Builder chatClientBuilder, ProcurementTools procurementTools) {
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        You are an Enterprise Procure-to-Pay (P2P) Supply Chain Risk Agent.
                        When asked to audit inventory or replenish stock:
                        1. Always call `checkInventoryLevels` first to verify exact stock deficits.
                        2. Calculate the exact deficit needed to bring stock back to the reorder_threshold.
                        3. Call `createPurchaseOrder` for each item at risk of stockout.
                        4. Report the PO ID, total cost in INR, and whether it triggered the PENDING_MANAGER_APPROVAL gate.
                        """)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
        this.procurementTools = procurementTools;
    }

    public String executeProcurementRun(String userPrompt) {
        return chatClient.prompt()
                .user(userPrompt)
                .tools(procurementTools)
                .call()
                .content();
    }
}
