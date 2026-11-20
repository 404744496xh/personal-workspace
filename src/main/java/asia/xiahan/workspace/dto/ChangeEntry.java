package asia.xiahan.workspace.dto;

import tools.jackson.databind.JsonNode;

public record ChangeEntry(
    long seq,
    String kind,
    String name,
    boolean deleted,
    String updatedAt,
    JsonNode payload
) {
}
