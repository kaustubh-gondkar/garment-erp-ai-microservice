package com.kaustubh.ai_microservice.memory;


import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class JdbcSlidingWindowChatMemory implements ChatMemory {

    private static final int SLIDING_WINDOW_SIZE = 6; // Keeps last 6 messages (3 turns)
    private final JdbcTemplate jdbcTemplate;

    public JdbcSlidingWindowChatMemory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String sql = "INSERT INTO p2p_chat_memory (conversation_id, message_type, content) VALUES (?, ?, ?)";
        for (Message msg : messages) {
            jdbcTemplate.update(sql, conversationId, msg.getMessageType().name(), msg.getText());
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        String sql = """
            SELECT message_type, content
            FROM p2p_chat_memory
            WHERE conversation_id = ?
            ORDER BY created_at DESC
            LIMIT ?
            """;
        List<Message> recentMessages = jdbcTemplate.query(sql, (rs, rowNum) -> {
            String type = rs.getString("message_type");
            String content = rs.getString("content");
            return switch (type) {
                case "USER" -> new UserMessage(content);
                case "ASSISTANT" -> new AssistantMessage(content);
                default -> new SystemMessage(content);
            };
        }, conversationId, SLIDING_WINDOW_SIZE);

        Collections.reverse(recentMessages);
        return recentMessages;
    }

    @Override
    public void clear(String conversationId) {
        jdbcTemplate.update("DELETE FROM p2p_chat_memory WHERE conversation_id = ?", conversationId);
    }
}
