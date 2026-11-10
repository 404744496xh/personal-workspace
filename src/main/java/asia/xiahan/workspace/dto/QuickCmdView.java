package asia.xiahan.workspace.dto;

import asia.xiahan.workspace.WorkspaceQuickCmd;
import java.util.List;

public record QuickCmdView(
    String name,
    String title,
    String command,
    String note,
    String category,
    List<String> tags
) {

    public static QuickCmdView of(WorkspaceQuickCmd command) {
        var spec = command.getSpec();
        return new QuickCmdView(
            command.getMetadata().getName(),
            spec.getTitle(),
            spec.getCommand(),
            spec.getNote(),
            spec.getCategory(),
            spec.getTags() == null ? List.of() : List.copyOf(spec.getTags())
        );
    }
}
