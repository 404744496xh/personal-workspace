package asia.xiahan.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record QuickCmdUpsertRequest(
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 5000) String command,
    @Size(max = 2000) String note,
    @Size(max = 32) String category,
    @Size(max = 10) List<@NotBlank @Size(max = 32) String> tags,
    @Size(max = 64) String owner
) {
}
