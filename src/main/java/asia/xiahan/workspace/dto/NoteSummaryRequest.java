package asia.xiahan.workspace.dto;

import jakarta.validation.constraints.Size;

public record NoteSummaryRequest(
    @Size(max = 200) String title,
    @Size(max = 200000) String content,
    @Size(max = 64) String note
) {
}
