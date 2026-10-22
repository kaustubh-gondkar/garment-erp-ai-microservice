package com.kaustubh.ai_microservice.harness;

import com.kaustubh.ai_microservice.exception.AgentLoopTerminationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LoopGuardAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LoopGuardAdvisor.class);
    public static final String WORKFLOW_RUN_ID_KEY = "WORKFLOW_RUN_ID";
    private static final int MAX_ALLOWED_STEPS = 5;
    private static final int MAX_TOKEN_BUDGET = 8000;

    private final Map<String, AtomicInteger> stepTracker = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> tokenTracker = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return "LoopGuardAdvisor";
    }

    @Override
    public int getOrder() {
        // High precedence: wrap the model invocation before standard advisors
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 1. Extract workflow run ID from advise context safely
        Map<String, Object> context = request.context();
        Object rawRunId = (context != null) ? context.get(WORKFLOW_RUN_ID_KEY) : null;
        String runId = (rawRunId != null) ? rawRunId.toString() : "default-run";

        stepTracker.putIfAbsent(runId, new AtomicInteger(0));
        tokenTracker.putIfAbsent(runId, new AtomicInteger(0));

        // 2. Increment and check step circuit breaker
        int currentStep = stepTracker.get(runId).incrementAndGet();
        log.info("[LOOP_GUARD] Executing step {} for runId: {}", currentStep, runId);

        if (currentStep > MAX_ALLOWED_STEPS) {
            log.error("[LOOP_GUARD] Circuit breaker tripped! Run {} exceeded max steps ({})", runId, MAX_ALLOWED_STEPS);
            throw new AgentLoopTerminationException(
                    "Circuit breaker triggered: Agent exceeded maximum step limit of " + MAX_ALLOWED_STEPS
            );
        }

        // 3. Forward request through the advisor chain to Gemini / tools
        ChatClientResponse response = chain.nextCall(request);

        // 4. Track cumulative token usage from model response metadata
        if (response != null && response.chatResponse() != null && response.chatResponse().getMetadata() != null) {
            Usage usage = response.chatResponse().getMetadata().getUsage();
            if (usage != null && usage.getTotalTokens() != null) {
                int totalTokens = tokenTracker.get(runId).addAndGet(usage.getTotalTokens().intValue());
                log.info("[LOOP_GUARD] Run {} | Step {} | Cumulative Tokens: {}/{}",
                        runId, currentStep, totalTokens, MAX_TOKEN_BUDGET);

                if (totalTokens > MAX_TOKEN_BUDGET) {
                    log.error("[LOOP_GUARD] Token budget tripped! Run {} used {} tokens", runId, totalTokens);
                    throw new AgentLoopTerminationException(
                            "Circuit breaker triggered: Token budget exceeded (" + totalTokens + " > " + MAX_TOKEN_BUDGET + ")"
                    );
                }
            }
        }

        return response;
    }

    public int getStepCount(String runId) {
        int count = stepTracker.getOrDefault(runId, new AtomicInteger(0)).get();
        if (count == 0 && stepTracker.containsKey("default-run")) {
            return stepTracker.get("default-run").get();
        }
        return count;
    }

    public int getTokenCount(String runId) {
        int tokens = tokenTracker.getOrDefault(runId, new AtomicInteger(0)).get();
        if (tokens == 0 && tokenTracker.containsKey("default-run")) {
            return tokenTracker.get("default-run").get();
        }
        return tokens;
    }

    public void cleanup(String runId) {
        stepTracker.remove(runId);
        tokenTracker.remove(runId);
        stepTracker.remove("default-run");
        tokenTracker.remove("default-run");
    }
}