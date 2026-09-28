package com.mall.agent.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DemoProductManifestTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsUniquePositiveProductIdsFromManifest() throws Exception {
        Path manifest = tempDir.resolve("demo-products.local.json");
        Files.writeString(manifest, """
                {"ceramic-cup":101,"linen-tote":202}
                """);

        Set<Long> ids = DemoProductManifest.load(manifest);

        assertEquals(Set.of(101L, 202L), ids);
        assertThrows(UnsupportedOperationException.class, () -> ids.add(303L));
    }

    @Test
    void invalidManifestDisablesOnlyProductAnswers() throws Exception {
        Path missing = tempDir.resolve("missing.json");
        Path duplicateIds = tempDir.resolve("duplicate-ids.json");
        Path duplicateKeys = tempDir.resolve("duplicate-keys.json");
        Path nonpositiveId = tempDir.resolve("nonpositive-id.json");
        Path negativeId = tempDir.resolve("negative-id.json");
        Path malformed = tempDir.resolve("malformed.json");
        Files.writeString(duplicateIds, "{\"cup\":101,\"tote\":101}");
        Files.writeString(duplicateKeys, "{\"cup\":101,\"cup\":202}");
        Files.writeString(nonpositiveId, "{\"cup\":0}");
        Files.writeString(negativeId, "{\"cup\":-1}");
        Files.writeString(malformed, "not json");

        assertEquals(Set.of(), DemoProductManifest.load(missing));
        assertEquals(Set.of(), DemoProductManifest.load(duplicateIds));
        assertEquals(Set.of(), DemoProductManifest.load(duplicateKeys));
        assertEquals(Set.of(), DemoProductManifest.load(nonpositiveId));
        assertEquals(Set.of(), DemoProductManifest.load(negativeId));
        assertEquals(Set.of(), DemoProductManifest.load(malformed));

        AtomicInteger productToolCalls = new AtomicInteger();
        CurrentProductIndex index = new CurrentProductIndex(request -> {
            productToolCalls.incrementAndGet();
            throw new AssertionError("an invalid manifest must disable product MCP calls");
        });
        assertTrue(index.refresh(DemoProductManifest.load(missing)).productIds().isEmpty());
        assertEquals(0, productToolCalls.get());
    }
}
