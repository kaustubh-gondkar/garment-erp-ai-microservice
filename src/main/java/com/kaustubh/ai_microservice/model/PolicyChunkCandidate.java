package com.kaustubh.ai_microservice.model;


import java.util.UUID;

public record PolicyChunkCandidate(
        UUID chunkId,
        String docCode,
        String vendorId,
        String category,
        String sectionTitle,
        String content,
        Long vectorRank,
        Long lexicalRank,
        double rrfScore
) {}