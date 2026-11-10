package asia.xiahan.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ModuleRenameRequest(
    @NotBlank @Size(max = 32) String from,
    @NotBlank @Size(max = 32) String to
) {
}