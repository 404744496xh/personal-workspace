package asia.xiahan.workspace.dto;

import java.util.List;
import java.util.Map;

public record BackupManifest(
    String format,
    String version,
    String exportedAt,
    String baseUrl,
    Map<String, Integer> counts,
    List<BackupAttachment> attachments,
    List<String> warnings
) {

    public static final String FORMAT = "personal-workspace-backup";
    public static final String VERSION = "2";

    public record BackupAttachment(
        String path,
        String url,
        String filename,
        String mediaType,
        long size,
        String sha256
    ) {
    }
}
