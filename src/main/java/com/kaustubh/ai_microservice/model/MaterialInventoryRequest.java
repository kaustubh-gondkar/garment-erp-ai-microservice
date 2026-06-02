package com.kaustubh.ai_microservice.model;

public record MaterialInventoryRequest(String materialName, Double quantity, String unit) {
}
