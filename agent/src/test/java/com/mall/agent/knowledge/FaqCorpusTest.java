package com.mall.agent.knowledge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaqCorpusTest {

    @Test
    void loadsExactly32UniqueGroundedFaqs() {
        List<FaqCorpus.FaqDocument> documents = FaqCorpus.load();

        assertEquals(32, documents.size());
        assertEquals(IntStream.rangeClosed(1, 32)
                .mapToObj(number -> "FAQ-%03d".formatted(number)).toList(),
                documents.stream().map(FaqCorpus.FaqDocument::id).toList());
        for (FaqCorpus.FaqDocument document : documents) {
            assertFalse(document.title().isBlank());
            assertFalse(document.body().isBlank());
            assertFalse(document.basis().isBlank());
            assertFalse(document.reviewedAt().isBlank());
            for (String citation : document.basis().split(";")) {
                String sourcePath = citation.strip().split("#", 2)[0];
                assertTrue(Files.isRegularFile(repositoryRoot().resolve(sourcePath)),
                        () -> document.id() + " basis path does not exist: " + sourcePath);
            }
        }
    }

    @Test
    void rejectsMissingBasisOrReviewDate() {
        assertThrows(IllegalArgumentException.class,
                () -> FaqCorpus.parse(corpusWithOmittedField("basis")));
        assertThrows(IllegalArgumentException.class,
                () -> FaqCorpus.parse(corpusWithOmittedField("reviewedAt")));
    }

    private static String corpusWithOmittedField(String omittedField) {
        StringBuilder json = new StringBuilder("[");
        for (int number = 1; number <= 32; number++) {
            if (number > 1) json.append(',');
            json.append("{\"id\":\"FAQ-%03d\",\"title\":\"标题\",\"body\":\"正文\""
                    .formatted(number));
            if (!omittedField.equals("basis")) {
                json.append(",\"basis\":\"docs/plans/2026-09-26-phase3b-knowledge-qa.md#Task-1\"");
            }
            if (!omittedField.equals("reviewedAt")) {
                json.append(",\"reviewedAt\":\"2026-09-28\"");
            }
            json.append('}');
        }
        return json.append(']').toString();
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("AGENTS.md"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("repository root not found");
        return current;
    }
}
