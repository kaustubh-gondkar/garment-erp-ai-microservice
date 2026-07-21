package com.kaustubh.ai_microservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.kaustubh.ai_microservice.entity.ApiCostLog;

@Repository
public interface ApiCostLogRepository extends JpaRepository<ApiCostLog, Long> {
}
