package asia.xiahan.workspace.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BackupPayload(
    @NotBlank @Size(max = 16) String version,
    @Size(max = 40) String exportedAt,
    @Valid @Size(max = 5000) List<BackupTodo> todos,
    @Valid @Size(max = 500) List<BackupProject> projects,
    @Valid @Size(max = 3000) List<BackupCommand> commands,
    @Valid @Size(max = 3000) List<BackupNote> notes
) {

    public record BackupTodo(
        @Size(max = 64) String name,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String detail,
        @Pattern(regexp = "HIGH|MEDIUM|LOW") String priority,
        @Size(max = 64) String project,
        @Size(max = 32) String module,
        @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String dueDate,
        Boolean completed,
        @Size(max = 40) String completedAt
    ) {
    }

    public record BackupProject(
        @Size(max = 64) String name,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 20) List<@NotBlank @Size(max = 32) String> modules,
        Integer progress,
        @Size(max = 2000) String note,
        @Size(max = 20) List<@NotBlank @Size(max = 2000) String> links,
        @Size(max = 10) List<@NotBlank @Size(max = 32) String> tags
    ) {
    }

    public record BackupCommand(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 5000) String command,
        @Size(max = 2000) String note,
        @Size(max = 32) String category,
        @Size(max = 10) List<@NotBlank @Size(max = 32) String> tags
    ) {
    }

    public record BackupNote(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 200000) String content,
        @Size(max = 10) List<@NotBlank @Size(max = 32) String> tags,
        @Size(max = 64) String project,
        @Size(max = 32) String module,
        @Size(max = 50) List<@NotBlank @Size(max = 64) String> todos,
        @Size(max = 40) String updatedAt
    ) {
    }
}
