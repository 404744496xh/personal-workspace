package asia.xiahan.workspace.dto;

import asia.xiahan.workspace.WorkspaceProject;
import java.util.List;

public record ProjectView(
    String name,
    String title,
    List<String> modules,
    String status,
    String note,
    List<String> links,
    List<String> tags
) {

    public static String statusOf(WorkspaceProject project) {
        return statusOf(project.getSpec());
    }

    public static String statusOf(WorkspaceProject.Spec spec) {
        var status = spec.getStatus();
        return status == null || status.isBlank() ? "DEV" : status;
    }

    public static ProjectView of(WorkspaceProject project) {
        var spec = project.getSpec();
        return new ProjectView(
            project.getMetadata().getName(),
            spec.getTitle(),
            spec.getModules() == null ? List.of() : List.copyOf(spec.getModules()),
            statusOf(spec),
            spec.getNote(),
            spec.getLinks() == null ? List.of() : List.copyOf(spec.getLinks()),
            spec.getTags() == null ? List.of() : List.copyOf(spec.getTags())
        );
    }
}
