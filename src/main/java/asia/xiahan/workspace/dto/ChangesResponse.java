package asia.xiahan.workspace.dto;

import java.util.List;

public record ChangesResponse(
    Long cursor,
    Long oldestSeq,
    boolean hasMore,
    List<ChangeEntry> changes
) {
}
