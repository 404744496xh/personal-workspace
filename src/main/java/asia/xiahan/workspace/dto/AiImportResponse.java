package asia.xiahan.workspace.dto;

import java.util.List;

public record AiImportResponse(
    List<AiImportRow> rows
) {
}
