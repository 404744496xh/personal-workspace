package asia.xiahan.workspace.dto;

public record AiImportRow(
    String project,
    String module,
    String title,
    String detail,
    String createdAt,
    String reporter
) {
}
