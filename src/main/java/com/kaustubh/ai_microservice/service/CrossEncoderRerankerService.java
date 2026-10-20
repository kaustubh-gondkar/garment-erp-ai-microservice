package com.kaustubh.ai_microservice.service;


import com.kaustubh.ai_microservice.model.PolicyChunkCandidate;
import com.kaustubh.ai_microservice.model.RerankedPolicyView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CrossEncoderRerankerService {

    private static final double MIN_RELEVANCE_THRESHOLD = 0.15;

    /**
     * Reranks Top-N RRF candidates down to Top-K high-precision chunks.
     * Consumes 0 Gemini API requests while filtering out false-positive dense neighbors.
     */
    public List<RerankedPolicyView> rerank(String query, List<PolicyChunkCandidate> candidates, int finalTopK) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(query);
        List<String> queryBigrams = buildBigrams( new ArrayList<>(queryTokens));

        List<RerankedPolicyView> scored = candidates.stream()
                .map(c -> scoreCandidatePair(query, queryTokens, queryBigrams, c))
                .filter(r -> r.crossEncoderScore() >= MIN_RELEVANCE_THRESHOLD)
                .sorted(Comparator.comparingDouble(RerankedPolicyView::crossEncoderScore).reversed())
                .limit(finalTopK)
                .toList();

        log.info("Cross-Encoder Reranker condensed {} RRF candidates -> {} verified chunks",
                candidates.size(), scored.size());
        return scored;
    }

    private RerankedPolicyView scoreCandidatePair(
            String rawQuery,
            Set<String> queryTokens,
            List<String> queryBigrams,
            PolicyChunkCandidate candidate) {

        String fullDocText = (candidate.docCode() + " " + candidate.sectionTitle() + " " + candidate.content()).toLowerCase();
        Set<String> docTokens = tokenize(fullDocText);

        // 1. Token coverage ratio (Unigram interaction)
        long matchedTokens = queryTokens.stream().filter(docTokens::contains).count();
        double tokenOverlapScore = queryTokens.isEmpty() ? 0.0 : (double) matchedTokens / queryTokens.size();

        // 2. Phrase proximity boost (Bigram interaction)
        long matchedBigrams = queryBigrams.stream().filter(fullDocText::contains).count();
        double bigramBoost = queryBigrams.isEmpty() ? 0.0 : ((double) matchedBigrams / queryBigrams.size()) * 0.25;

        // 3. Exact code / vendor ID / clause match boost (Crucial for P2P SLAs & HSN codes)
        double exactIdentifierBoost = 0.0;
        if (candidate.docCode() != null && rawQuery.toUpperCase().contains(candidate.docCode().toUpperCase())) {
            exactIdentifierBoost += 0.35;
        }
        if (candidate.vendorId() != null && rawQuery.toUpperCase().contains(candidate.vendorId().toUpperCase())) {
            exactIdentifierBoost += 0.25;
        }

        // 4. Dual-engine consensus boost (Appeared in BOTH pgvector AND tsvector top-20)
        double consensusBoost = (candidate.vectorRank() != null && candidate.lexicalRank() != null) ? 0.20 : 0.0;

        // Normalize RRF base (max theoretical RRF with k=60 is ~1/61 = 0.01639 -> scaled to 0..1)
        double normalizedRrf = Math.min(1.0, candidate.rrfScore() * 61.0);

        double finalCrossScore = Math.min(1.0,
                (normalizedRrf * 0.45) +
                        (tokenOverlapScore * 0.35) +
                        bigramBoost +
                        exactIdentifierBoost +
                        consensusBoost
        );

        return new RerankedPolicyView(
                candidate.docCode(),
                candidate.vendorId(),
                candidate.sectionTitle(),
                candidate.content(),
                candidate.rrfScore(),
                Math.round(finalCrossScore * 1000.0) / 1000.0,
                finalCrossScore >= 0.40
        );
    }

    private Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("[^a-z0-9-]+"))
                .filter(t -> t.length() > 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> buildBigrams(List<String> tokens) {
        List<String> bigrams = new ArrayList<>();
        for (int i = 0; i < tokens.size() - 1; i++) {
            bigrams.add(tokens.get(i) + " " + tokens.get(i + 1));
        }
        return bigrams;
    }
}