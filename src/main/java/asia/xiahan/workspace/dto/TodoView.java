package asia.xiahan.workspace.dto;

import asia.xiahan.workspace.WorkspaceTodo;

public record TodoView(
    String name,
    String title,
    String detail,
    String priority,
    String project,
    String module,
    String dueDate,
    boolean completed,
    String completedAt,
    String createdAt
) {

    public static TodoView of(WorkspaceTodo todo) {
        var spec = todo.getSpec();
        var created = todo.getMetadata().getCreationTimestamp();
        return new TodoView(
            todo.getMetadata().getName(),
            spec.getTitle(),
            spec.getDetail(),
            spec.getPriority(),
            spec.getProject(),
            spec.getModule(),
            spec.getDueDate(),
            spec.isCompleted(),
            spec.getCompletedAt(),
            created == null ? null : created.toString()
        );
    }
}
