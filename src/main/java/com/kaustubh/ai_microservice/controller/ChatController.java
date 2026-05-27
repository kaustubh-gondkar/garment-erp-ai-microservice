package com.kaustubh.ai_microservice.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kaustubh.ai_microservice.service.PdfIngestionService;

@RestController
public class ChatController {

	private final ChatClient chatClient;

	private final PdfIngestionService pdfIngestionService;

	// Spring Boot auto-configures the builder using your API key from
	// application.yml
	public ChatController(ChatClient.Builder builder, PdfIngestionService pdfIngestionService) {
		InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();

		ChatMemory chatMemory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(20)
				.build();
//		this.chatClient = builder.build();

		this.chatClient = builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build();

		this.pdfIngestionService = pdfIngestionService;
	}

	// 1. We define the exact JSON structure we want the AI to return
	public record StorageAdvice(String materialName, String optimalConditions, boolean needsClimateControl) {
	}

	@GetMapping("/api/chat")
	public String chatWithAi(
			@RequestParam(value = "message", defaultValue = "Tell me a short joke about Java developers") String message) {
		return chatClient.prompt(message).call().content();
	}

	@GetMapping("/api/chat/garment")
	public String analyzeMaterial(@RequestParam(value = "material", defaultValue = "cotton") String material) {

		return chatClient.prompt().system(
				"You are an expert manufacturing assistant for a garment factory. Give short, one-sentence advice on how to store the requested raw material.")
				.user("How should I store: " + material + "?").call().content();
	}

	@GetMapping("/api/chat/garment/structured")
	public StorageAdvice analyzeMaterialStructured(
			@RequestParam(value = "material", defaultValue = "silk") String material) {

		return chatClient.prompt().system(
				"You are an expert manufacturing assistant for a garment factory. Analyze the storage requirements for the requested raw material.")
				.user("Analyze this material: " + material).call()
				// 2. We command the LLM to format its response to perfectly match our Java
				// record
				.entity(StorageAdvice.class);
	}

	@GetMapping("/api/chat/garment/conversation")
	public String converse(@RequestParam(value = "message") String message) {
		return chatClient.prompt()
				.system("You are an expert manufacturing assistant for a garment factory. Keep answers brief.")
				.user(message).advisors(a -> a.param("chat_memory_conversation_id", "actual_user_it_here")).call()
				.content();
	}

	@GetMapping("/ingest")
	public String triggerPdfIngestion() {
		return pdfIngestionService.ingestPdf();
	}

}
