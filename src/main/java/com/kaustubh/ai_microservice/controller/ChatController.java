package com.kaustubh.ai_microservice.controller;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.kaustubh.ai_microservice.service.InventoryAiService;
import com.kaustubh.ai_microservice.service.PdfIngestionService;
import com.kaustubh.ai_microservice.service.RagService;

import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;

@RestController
public class ChatController {

	private final ChatClient chatClient;

	private final PdfIngestionService pdfIngestionService;
	private final RagService ragService;
	private final InventoryAiService inventoryAiService;

	// Spring Boot auto-configures the builder using your API key from
	// application.yml
	public ChatController(ChatClient.Builder builder, PdfIngestionService pdfIngestionService, RagService ragService,InventoryAiService inventoryAiService) {
		InMemoryChatMemoryRepository repository = new InMemoryChatMemoryRepository();

		ChatMemory chatMemory = MessageWindowChatMemory.builder().chatMemoryRepository(repository).maxMessages(20)
				.build();
//		this.chatClient = builder.build();

		this.chatClient = builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build()).build();

		this.pdfIngestionService = pdfIngestionService;
		this.ragService = ragService;
		this.inventoryAiService = inventoryAiService;
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

//	@GetMapping("/ingest")
//	public String triggerPdfIngestion() {
//		return pdfIngestionService.ingestPdf();
//	}

	// Milestone 2.4: The RAG Search Endpoint
	@GetMapping("/search")
	public String searchPdf(@RequestParam String query) {
		return ragService.askDocument(query);
	}

	@PostMapping("/upload")
	public String uploadPdf(@RequestParam("file") MultipartFile file) {
		return pdfIngestionService.ingestUploadedFile(file);
	}
	
    @GetMapping("/chat/agent")
    public String agenticChat(@RequestParam("message") String message) {
        return chatClient.prompt()
                .user(message)
                .tools(inventoryAiService)
                .advisors(advisorSpec -> advisorSpec
                        .param("conversationId", "agent-session-123")
                        .param("chat_memory_conversation_id", "agent-session-123")
                )
                .call()
                .content();
    }
    
  

    // ... inside your ChatController

        @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public Flux<String> streamAgenticChat(@RequestParam("message") String message) {
            
            return chatClient.prompt()
                    .user(message)
                    .tools(inventoryAiService)
                    .advisors(a -> a
                            .advisors(new SimpleLoggerAdvisor())
                            .param("conversationId", "agent-session-123")
                            .param("chat_memory_conversation_id", "agent-session-123")
                        )
                    .stream()  
                    .content();
        }


}
