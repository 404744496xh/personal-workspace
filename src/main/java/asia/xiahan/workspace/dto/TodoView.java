package asia.xiahan.workspace.dto;

import asia.xiahan.workspace.WorkspaceTodo;

public record TodoView(
    String name,
    String title,
    String detail,
    String priority,
    String project,
    String module,
    String projectStatus,
    String reporter,
    String dueDate,
    boolean completed,
    String completedAt,
    boolean dismissed,
    String dismissedAt,
    boolean pinned,
    String createdAt
) {

    public static TodoView of(WorkspaceTodo todo) {
        var spec = todo.getSpec();
        var created = spec.getCreatedAt() != null
            ? spec.getCreatedAt()
            : todo.getMetadata().getCreationTimestamp() == null
                ? null : todo.getMetadata().getCreationTimestamp().toString();
        return new TodoView(
            todo.getMetadata().getName(),
            spec.getTitle(),
            spec.getDetail(),
            spec.getPriority(),
            spec.getProject(),
            spec.getModule(),
            spec.getProjectStatus(),
            spec.getReporter(),
            spec.getDueDate(),
            spec.isCompleted(),
            spec.getCompletedAt(),
            spec.isDismissed(),
            spec.getDismissedAt(),
            spec.isPinned(),
            created
        );
    }
}
