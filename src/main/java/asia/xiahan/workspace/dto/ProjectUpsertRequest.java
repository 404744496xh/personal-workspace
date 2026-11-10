package asia.xiahan.workspace.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProjectUpsertRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 20) List<@NotBlank @Size(max = 32) String> modules,
    @Min(0) @Max(100) Integer progress,
    @Size(max = 2000) String note,
    @Size(max = 20) List<@NotBlank @Size(max = 2000) String> links,
    @Size(max = 10) List<@NotBlank @Size(max = 32) String> tags,
    @Size(max = 64) String owner
) {
}
