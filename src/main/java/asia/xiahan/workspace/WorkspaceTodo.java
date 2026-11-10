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
    kind = "WorkspaceTodo",
    plural = "workspacetodos",
    singular = "workspacetodo"
)
public class WorkspaceTodo extends AbstractExtension {

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

        @Schema(requiredMode = REQUIRED, description = "Todo title", maxLength = 200)
        private String title;

        @Schema(description = "Todo detail", maxLength = 5000)
        private String detail;

        @Schema(
            requiredMode = REQUIRED,
            description = "Todo priority",
            allowableValues = {"HIGH", "MEDIUM", "LOW"}
        )
        private String priority;

        @Schema(description = "Tags, de-duplicated by the server")
        private List<String> tags = new ArrayList<>();

        @Schema(description = "Owning project name", maxLength = 64)
        private String project;

        @Schema(description = "Module name inside the project", maxLength = 32)
        private String module;

        @Schema(description = "Due date in YYYY-MM-DD", maxLength = 10)
        private String dueDate;

        @Schema(description = "Whether the todo is completed")
        private boolean completed;

        @Schema(description = "Completion time in ISO-8601, written by the server")
        private String completedAt;

        @Schema(description = "Whether the todo is dismissed and needs no action")
        private boolean dismissed;

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

        public String getDetail() {
            return detail;
        }

        public void setDetail(String detail) {
            this.detail = detail;
        }

        public String getPriority() {
            return priority;
        }

        public void setPriority(String priority) {
            this.priority = priority;
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

        public String getDueDate() {
            return dueDate;
        }

        public void setDueDate(String dueDate) {
            this.dueDate = dueDate;
        }

        public boolean isCompleted() {
            return completed;
        }

        public void setCompleted(boolean completed) {
            this.completed = completed;
        }

        public String getCompletedAt() {
            return completedAt;
        }

        public void setCompletedAt(String completedAt) {
            this.completedAt = completedAt;
        }

        public boolean isDismissed() {
            return dismissed;
        }

        public void setDismissed(boolean dismissed) {
            this.dismissed = dismissed;
        }
    }
}
