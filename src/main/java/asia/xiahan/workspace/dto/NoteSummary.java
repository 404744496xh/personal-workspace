package asia.xiahan.workspace.dto;

import asia.xiahan.workspace.WorkspaceNote;
import java.time.Instant;
import java.util.List;

public record NoteSummary(
    String name,
    String title,
    String excerpt,
    List<String> tags,
    String project,
    String module,
    List<String> todos,
    String updatedAt
) {

    private static final int EXCERPT_MAX = 220;

    public static NoteSummary of(WorkspaceNote note) {
        var spec = note.getSpec();
        var updatedAt = spec.getUpdatedAt();
        if (updatedAt == null || updatedAt.isBlank()) {
            updatedAt = note.getMetadata().getCreationTimestamp() == null
                ? Instant.now().toString()
                : note.getMetadata().getCreationTimestamp().toString();
        }
        return new NoteSummary(
            note.getMetadata().getName(),
            spec.getTitle(),
            excerpt(spec.getContent()),
            spec.getTags() == null ? List.of() : List.copyOf(spec.getTags()),
            spec.getProject(),
            spec.getModule(),
            spec.getTodos() == null ? List.of() : List.copyOf(spec.getTodos()),
            updatedAt
        );
    }

    private static String excerpt(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        var text = content
            .replaceAll("(?s)```.*?```", " ")
            .replaceAll("!\\[[^]]*]\\([^)]*\\)", " ")
            .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
            .replaceAll("[#>*_`~]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return text.length() > EXCERPT_MAX ? text.substring(0, EXCERPT_MAX) + "…" : text;
    }
}
