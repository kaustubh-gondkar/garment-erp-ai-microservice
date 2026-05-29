package com.kaustubh.ai_microservice.service;

import org.aspectj.weaver.patterns.TypePatternQuestions.Question;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class RagService {

	private final ChatClient chatClient;
	private final VectorStore vectorStore;

	public RagService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
		this.chatClient = chatClientBuilder.build();
		this.vectorStore = vectorStore;
	}

	public String askDocument(String question) {
		return chatClient.prompt().user(question)
				// This searches PgVector for the matching PDF paragraphs and gives them to
				// Gemini
				.advisors(QuestionAnswerAdvisor.builder(vectorStore).build()).call().content();
	}
}
