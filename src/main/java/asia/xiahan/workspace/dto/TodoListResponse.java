package asia.xiahan.workspace.dto;

import java.util.List;

public record TodoListResponse(List<TodoView> items, long total, int page, int size) {
}
