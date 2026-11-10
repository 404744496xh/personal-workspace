package asia.xiahan.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TodoUpsertRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 5000) String detail,
    @NotNull @Pattern(regexp = "HIGH|MEDIUM|LOW") String priority,
    @Size(max = 64) String project,
    @Size(max = 32) String module,
    @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") String dueDate,
    Boolean completed,
    Boolean dismissed,
    @Size(max = 64) String owner
) {
}
