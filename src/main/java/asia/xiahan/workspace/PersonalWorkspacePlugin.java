package asia.xiahan.workspace;

import java.util.Set;
import org.springframework.stereotype.Component;
import run.halo.app.extension.Scheme;
import run.halo.app.extension.SchemeManager;
import run.halo.app.extension.index.IndexSpecs;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

@Component
public class PersonalWorkspacePlugin extends BasePlugin {

    private final SchemeManager schemeManager;

    public PersonalWorkspacePlugin(PluginContext pluginContext, SchemeManager schemeManager) {
        super(pluginContext);
        this.schemeManager = schemeManager;
    }

    @Override
    public void start() {
        schemeManager.register(WorkspaceTodo.class, specs -> {
            specs.add(IndexSpecs.<WorkspaceTodo, String>single("spec.owner", String.class)
                .indexFunc(todo -> todo.getSpec().getOwner()));
            specs.add(IndexSpecs.<WorkspaceTodo, Boolean>single("spec.completed", Boolean.class)
                .indexFunc(todo -> todo.getSpec().isCompleted()));
            specs.add(IndexSpecs.<WorkspaceTodo, String>single("spec.priority", String.class)
                .indexFunc(todo -> todo.getSpec().getPriority()));
            specs.add(IndexSpecs.<WorkspaceTodo, String>single("spec.dueDate", String.class)
                .indexFunc(todo -> todo.getSpec().getDueDate()));
            specs.add(IndexSpecs.<WorkspaceTodo, String>single("spec.title", String.class)
                .indexFunc(todo -> todo.getSpec().getTitle()));
            specs.add(IndexSpecs.<WorkspaceTodo, String>single("spec.detail", String.class)
                .indexFunc(todo -> todo.getSpec().getDetail()));
            specs.add(IndexSpecs.<WorkspaceTodo, String>multi("spec.tags", String.class)
                .indexFunc(todo -> todo.getSpec().getTags() == null
                    ? Set.of()
                    : Set.copyOf(todo.getSpec().getTags())));
        });
        schemeManager.register(WorkspaceProject.class, specs ->
            specs.add(IndexSpecs.<WorkspaceProject, String>single("spec.owner", String.class)
                .indexFunc(project -> project.getSpec().getOwner())));
        schemeManager.register(WorkspaceQuickCmd.class, specs ->
            specs.add(IndexSpecs.<WorkspaceQuickCmd, String>single("spec.owner", String.class)
                .indexFunc(command -> command.getSpec().getOwner())));
        schemeManager.register(WorkspaceNote.class, specs ->
            specs.add(IndexSpecs.<WorkspaceNote, String>single("spec.owner", String.class)
                .indexFunc(note -> note.getSpec().getOwner())));
    }

    @Override
    public void stop() {
        schemeManager.unregister(Scheme.buildFromType(WorkspaceTodo.class));
        schemeManager.unregister(Scheme.buildFromType(WorkspaceProject.class));
        schemeManager.unregister(Scheme.buildFromType(WorkspaceQuickCmd.class));
        schemeManager.unregister(Scheme.buildFromType(WorkspaceNote.class));
    }
}
