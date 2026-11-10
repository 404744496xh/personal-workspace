package asia.xiahan.workspace.dto;

import asia.xiahan.workspace.WorkspaceNote;
import java.time.Instant;
import java.util.List;

public record NoteView(
    String name,
    String title,
    String content,
    List<String> tags,
    String project,
    String module,
    List<String> todos,
    String updatedAt
) {

    public static NoteView of(WorkspaceNote note) {
        var spec = note.getSpec();
        var updatedAt = spec.getUpdatedAt();
        if (updatedAt == null || updatedAt.isBlank()) {
            updatedAt = note.getMetadata().getCreationTimestamp() == null
                ? Instant.now().toString()
                : note.getMetadata().getCreationTimestamp().toString();
        }
        return new NoteView(
            note.getMetadata().getName(),
            spec.getTitle(),
            spec.getContent(),
            spec.getTags() == null ? List.of() : List.copyOf(spec.getTags()),
            spec.getProject(),
            spec.getModule(),
            spec.getTodos() == null ? List.of() : List.copyOf(spec.getTodos()),
            updatedAt
        );
    }
}
