package asia.xiahan.workspace;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

@GVK(
    group = "workspace.halo.run",
    version = "v1alpha1",
    kind = "WorkspaceNote",
    plural = "workspacenotes",
    singular = "workspacenote"
)
public class WorkspaceNote extends AbstractExtension {

    private Spec spec;

    public Spec getSpec() {
        return spec;
    }

    public void setSpec(Spec spec) {
        this.spec = spec;
    }

    public static class Spec {

        @Schema(
            requiredMode = REQUIRED,
            description = "Halo username of the owner, written by the server",
            maxLength = 64
        )
        private String owner;

        @Schema(requiredMode = REQUIRED, description = "Note title", maxLength = 200)
        private String title;

        @Schema(description = "Markdown content, may reference site attachment URLs", maxLength = 200000)
        private String content;

        @Schema(description = "Last update time in ISO-8601, written by the server", maxLength = 40)
        private String updatedAt;

        @Schema(description = "Tags, de-duplicated by the server")
        private List<String> tags = new ArrayList<>();

        @Schema(description = "Associated WorkspaceProject name, nullable", maxLength = 64)
        private String project;

        @Schema(description = "Associated module name within the project, nullable", maxLength = 32)
        private String module;

        @Schema(description = "Associated WorkspaceTodo names, de-duplicated by the server")
        private List<String> todos = new ArrayList<>();

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags == null ? new ArrayList<>() : tags;
        }

        public String getProject() {
            return project;
        }

        public void setProject(String project) {
            this.project = project;
        }

        public String getModule() {
            return module;
        }

        public void setModule(String module) {
            this.module = module;
        }

        public List<String> getTodos() {
            return todos;
        }

        public void setTodos(List<String> todos) {
            this.todos = todos == null ? new ArrayList<>() : todos;
        }
    }
}
