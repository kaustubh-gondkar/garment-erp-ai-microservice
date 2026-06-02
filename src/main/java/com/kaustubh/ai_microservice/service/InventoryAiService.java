package com.kaustubh.ai_microservice.service;

import com.kaustubh.ai_microservice.model.MaterialInventoryRequest;
import com.kaustubh.ai_microservice.model.MaterialInventoryResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
public class InventoryAiService {

	@Tool(description = "Create a new raw material tracking entry or log inventory updates for fabric, yarn, or accessories in the Micro-ERP system")
	public MaterialInventoryResponse updateMaterialInventory(MaterialInventoryRequest request) {

		System.out.println("🚀 AI triggered backend function with parameters:");
		System.out.println("Material: " + request.materialName());
		System.out.println("Quantity: " + request.quantity() + " " + request.unit());

		long simulatedId = (long) (Math.random() * 100000);

		return new MaterialInventoryResponse("SUCCESS", "Successfully logged " + request.quantity() + " "
				+ request.unit() + " of " + request.materialName() + " into inventory.", simulatedId);
	}
}
