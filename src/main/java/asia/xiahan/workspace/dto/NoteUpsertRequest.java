package asia.xiahan.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record NoteUpsertRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 200000) String content,
    @Size(max = 10) List<@NotBlank @Size(max = 32) String> tags,
    @Size(max = 64) String project,
    @Size(max = 32) String module,
    @Size(max = 50) List<@NotBlank @Size(max = 64) String> todos,
    @Size(max = 64) String owner
) {
}
