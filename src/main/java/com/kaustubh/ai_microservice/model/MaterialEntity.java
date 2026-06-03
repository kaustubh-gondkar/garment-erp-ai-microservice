
package com.kaustubh.ai_microservice.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "materials")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "material_name", nullable = false, unique = true)
	private String materialName;

	@Column(nullable = false)
	private Double quantity;

	@Column(nullable = false)
	private String unit;

	public MaterialEntity(String materialName, Double quantity, String unit) {
		this.materialName = materialName;
		this.quantity = quantity;
		this.unit = unit;
	}

}
