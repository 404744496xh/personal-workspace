package asia.xiahan.workspace.dto;

import java.util.List;

public record SearchResponse(String q, List<SearchHit> hits) {
}
