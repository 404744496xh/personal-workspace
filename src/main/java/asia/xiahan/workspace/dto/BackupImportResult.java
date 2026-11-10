package asia.xiahan.workspace.dto;

public record BackupImportResult(
    int todos,
    int projects,
    int commands,
    int notes
) {
}
