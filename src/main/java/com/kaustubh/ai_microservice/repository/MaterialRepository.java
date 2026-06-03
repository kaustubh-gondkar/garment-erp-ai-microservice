package com.kaustubh.ai_microservice.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.kaustubh.ai_microservice.model.MaterialEntity;

@Repository
public interface MaterialRepository extends JpaRepository<MaterialEntity, Long> {
	Optional<MaterialEntity> findByMaterialNameIgnoreCase(String materialName);

//    Optional<MaterialEntity> findFirstByMaterialNameContainingIgnoreCase(String materialName);
	List<MaterialEntity> findByMaterialNameContainingIgnoreCase(String materialName);
}
