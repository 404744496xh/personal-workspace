package asia.xiahan.workspace.dto;

import asia.xiahan.workspace.WorkspaceProject;
import java.util.List;

public record ProjectView(
    String name,
    String title,
    List<String> modules,
    int progress,
    String note,
    List<String> links,
    List<String> tags
) {

    public static ProjectView of(WorkspaceProject project) {
        var spec = project.getSpec();
        return new ProjectView(
            project.getMetadata().getName(),
            spec.getTitle(),
            spec.getModules() == null ? List.of() : List.copyOf(spec.getModules()),
            spec.getProgress() == null ? 0 : spec.getProgress(),
            spec.getNote(),
            spec.getLinks() == null ? List.of() : List.copyOf(spec.getLinks()),
            spec.getTags() == null ? List.of() : List.copyOf(spec.getTags())
        );
    }
}
