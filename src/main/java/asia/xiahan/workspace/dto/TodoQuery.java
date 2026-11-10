package asia.xiahan.workspace.dto;

public record TodoQuery(
    String keyword,
    String status,
    String priority,
    int page,
    int size
) {
}
