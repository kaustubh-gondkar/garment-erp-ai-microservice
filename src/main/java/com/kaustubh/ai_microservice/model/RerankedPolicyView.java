package com.kaustubh.ai_microservice.model;

public record RerankedPolicyView(
    String docCode,
    String vendorId,
    String sectionTitle,
    String content,
    double rrfScore,
    double crossEncoderScore,
    boolean citationVerified) {}
