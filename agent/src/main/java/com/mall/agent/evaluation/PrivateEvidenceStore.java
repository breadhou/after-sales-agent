package com.mall.agent.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Private local records and raw final responses; none are public Event fields. */
public class PrivateEvidenceStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final List<JsonNode> records = new ArrayList<>();

    public PrivateEvidenceStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    public synchronized String writeFinalReply(String sessionAlias, int turnIndex, String reply) {
        if (sessionAlias == null || !sessionAlias.matches("[a-z][a-z0-9-]{0,63}") || turnIndex < 0 || reply == null)
            throw new IllegalArgumentException("Invalid private final reply identity");
        String filename = "reply-" + sessionAlias + "-" + turnIndex + ".txt";
        write(filename, reply);
        append(JSON.createObjectNode().put("kind", "FINAL_REPLY").put("sessionAlias", sessionAlias)
                .put("turnIndex", turnIndex).put("callId", "reply-" + sessionAlias + "-" + turnIndex).put("file", filename));
        return filename;
    }

    synchronized void append(JsonNode record) {
        // Persist before acknowledging the record. A failed sink must be observable.
        List<JsonNode> updated = new ArrayList<>(records);
        updated.add(record.deepCopy());
        ArrayNode array = JSON.createArrayNode();
        updated.forEach(array::add);
        write("evidence.json", JSON.createObjectNode().put("schemaVersion", 1).set("records", array).toString());
        records.add(record.deepCopy());
    }

    public synchronized List<JsonNode> snapshot() { return records.stream().<JsonNode>map(JsonNode::deepCopy).toList(); }

    /** WorkerResult.privateEvidenceFile resolves this relative name in the trial work directory. */
    public String evidenceFile() { return "evidence.json"; }

    /** Creates even an empty private manifest for a worker with no source or reply records. */
    public synchronized String writeEvidenceFile() {
        ArrayNode array = JSON.createArrayNode();
        records.forEach(array::add);
        write(evidenceFile(), JSON.createObjectNode().put("schemaVersion", 1).set("records", array).toString());
        return evidenceFile();
    }

    private void write(String filename, String text) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(filename), text, StandardCharsets.UTF_8);
        } catch (IOException e) { throw new UncheckedIOException("Private evidence write failed", e); }
    }
}
