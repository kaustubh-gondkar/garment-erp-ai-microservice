package com.kaustubh.ai_microservice.service;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import com.kaustubh.ai_microservice.model.MaterialEntity;
import com.kaustubh.ai_microservice.model.MaterialInventoryRequest;
import com.kaustubh.ai_microservice.model.MaterialInventoryResponse;
import com.kaustubh.ai_microservice.repository.MaterialRepository;

import lombok.RequiredArgsConstructor;

//@Service
//public class InventoryAiService {
//
//	@Tool(description = "Create a new raw material tracking entry or log inventory updates for fabric, yarn, or accessories in the Micro-ERP system")
//	public MaterialInventoryResponse updateMaterialInventory(MaterialInventoryRequest request) {
//
//		System.out.println("🚀 AI triggered backend function with parameters:");
//		System.out.println("Material: " + request.materialName());
//		System.out.println("Quantity: " + request.quantity() + " " + request.unit());
//
//		long simulatedId = (long) (Math.random() * 100000);
//
//		return new MaterialInventoryResponse("SUCCESS", "Successfully logged " + request.quantity() + " "
//				+ request.unit() + " of " + request.materialName() + " into inventory.", simulatedId);
//	}
//}

@Service
@RequiredArgsConstructor
public class InventoryAiService {

	private final MaterialRepository materialRepository;

//	// TOOL 1: READ DETECTIVE
//	@Tool(description = "Check the current available stock levels or quantities for a specific raw material or fabric in the ERP database")
//	public String checkMaterialStock(String materialName) {
//		System.out.println("🔍 AI triggered READ tool for: " + materialName);
//
//		return materialRepository.findFirstByMaterialNameContainingIgnoreCase(materialName).map(
//				mat -> "Current stock for " + mat.getMaterialName() + " is " + mat.getQuantity() + " " + mat.getUnit())
//				.orElse("No inventory record found for " + materialName);
//	}

	// faced issue in above comented method - if two material have same names(linen
	// fabric and linen thread)
	@Cacheable(value = "materialStock", key = "#materialName.toLowerCase()")
	@Tool(description = "Check the current available stock levels. Returns a list of all matching materials. If the user's request is ambiguous, use this tool first to find the exact material name.")
	public String checkMaterialStock(String materialName) {
		System.out.println("🔍 AI triggered READ tool for: " + materialName);

		try {
			List<MaterialEntity> matches = materialRepository.findByMaterialNameContainingIgnoreCase(materialName);
			if (matches.isEmpty()) {
				return "No inventory record found containing the word: " + materialName;
			}
			// Build a formatted string of all matches found
			StringBuilder response = new StringBuilder("Found the following matching materials:\n");
			for (MaterialEntity mat : matches) {
				response.append("- ").append(mat.getMaterialName()).append(": ").append(mat.getQuantity()).append(" ")
						.append(mat.getUnit()).append("\n");
			}
			return response.toString();
		} catch (Exception e) {
			System.out.println("💥 FATAL ERROR IN TOOL: " + e.getMessage());
			e.printStackTrace(); // Prints the exact line that crashed to your console
			return "Internal database error occurred: " + e.getMessage();
		}
	}

	// TOOL 2: WRITE EXECUTIONER
	   @Caching(evict = {
		        @CacheEvict(value = "materialStock", key = "#request.materialName().toLowerCase()"),		        
				@CacheEvict(value = "allMaterialStock", allEntries = true) })
	@Tool(description = "Create a new raw material tracking entry or add stock. "
			+ "CRITICAL: You MUST have the exact material name, quantity, and unit of measurement. "
			+ "If the user does not specify the unit, DO NOT call this tool. Instead, reply to the user and ask them to specify the unit.")
	public MaterialInventoryResponse updateMaterialInventory(MaterialInventoryRequest request) {

		if (request.unit() == null || request.unit().isBlank()) {
			return new MaterialInventoryResponse("FAILED",
					"Missing unit. Please ask the user to provide the unit of measurement.", null);
		}

		System.out.println("🚀 AI triggered WRITE tool for: " + request.materialName());

		MaterialEntity entity = materialRepository.findByMaterialNameIgnoreCase(request.materialName())
				.orElse(new MaterialEntity(request.materialName(), 0.0, request.unit()));

		entity.setQuantity(entity.getQuantity() + request.quantity());
		MaterialEntity saved = materialRepository.save(entity);

		return new MaterialInventoryResponse("SUCCESS", "Successfully updated database stock.", saved.getId());
	}

	@Cacheable(value = "allMaterialStock")
	@Tool(description = "Check total available stock. CRITICAL: You must always present the final answer to the user as a bulleted list.")
	public String checkTotalStock() {
		System.out.println("🔍 AI triggered READ tool for: total stock");
		StringBuilder response = new StringBuilder("Found the following total stock:\n");

		List<MaterialEntity> matches = materialRepository.findAll();
		if (matches.isEmpty()) {
			return "No inventory record found";
		}

		for (MaterialEntity mat : matches) {
			response.append("- ").append(mat.getMaterialName()).append(": ").append(mat.getQuantity()).append(" ")
					.append(mat.getUnit()).append("\n");
		}
		return response.toString();
	}
}
