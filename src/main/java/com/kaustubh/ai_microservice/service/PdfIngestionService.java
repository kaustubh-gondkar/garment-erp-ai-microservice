package com.kaustubh.ai_microservice.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
public class PdfIngestionService {

	private final VectorStore vectorStore;

//	@Value("classpath:sample-document.pdf")
//	private Resource pdfResource;

	// Spring Boot automatically injects the PgVector store we built yesterday!
	public PdfIngestionService(VectorStore vectorStore) {
		this.vectorStore = vectorStore;
	}

//    public String ingestPdf() {
//        try {
//            // 1. Read the PDF
//            PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(pdfResource);
//            List<Document> documents = pdfReader.get();
//
//            // 2. Split the PDF into smaller, math-friendly chunks
//            TokenTextSplitter textSplitter = new TokenTextSplitter();
//            List<Document> splitDocuments = textSplitter.apply(documents);
//
//            // 3. Save the math (Embeddings) to PostgreSQL
//            vectorStore.add(splitDocuments);
//
//            return "Successfully ingested PDF and saved to PgVector database!";
//            
//        } catch (Exception e) {
//            return "Failed to ingest PDF: " + e.getMessage();
//        }
//    }

	public String ingestUploadedFile(MultipartFile file) {
		try {
			// 1. Convert the web upload into a Spring Resource
			Resource pdfResource = new ByteArrayResource(file.getBytes()) {
				@Override
				public String getFilename() {
					return file.getOriginalFilename();
				}
			};

			// 2. Read the PDF
			PagePdfDocumentReader pdfReader = new PagePdfDocumentReader(pdfResource);

			// 3. (Optional but recommended) Chunk the text into smaller pieces!
			TokenTextSplitter textSplitter = new TokenTextSplitter();

			// 4. Save to PgVector
			vectorStore.accept(textSplitter.apply(pdfReader.get()));

			return "Successfully ingested uploaded file: " + file.getOriginalFilename();

		} catch (IOException e) {
			return "Failed to process uploaded file: " + e.getMessage();
		}
	}
}
