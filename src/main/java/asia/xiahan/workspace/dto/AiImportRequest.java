package asia.xiahan.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiImportRequest(
    @NotBlank @Size(max = 20000) String text
) {
}
