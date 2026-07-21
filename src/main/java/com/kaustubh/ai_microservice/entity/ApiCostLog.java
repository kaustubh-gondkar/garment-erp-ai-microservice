package com.kaustubh.ai_microservice.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_cost_logs")
@Data
@NoArgsConstructor
public class ApiCostLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String operation;
	private Long inputTokens;
	private Long outputTokens;
	private LocalDateTime timestamp;

	public ApiCostLog(String operation, Long inputTokens, Long outputTokens) {
		this.operation = operation;
		this.inputTokens = inputTokens;
		this.outputTokens = outputTokens;
		this.timestamp = LocalDateTime.now();
	}

}
