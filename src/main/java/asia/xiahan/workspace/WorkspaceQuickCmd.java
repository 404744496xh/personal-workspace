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
    kind = "WorkspaceQuickCmd",
    plural = "workspacequickcmds",
    singular = "workspacequickcmd"
)
public class WorkspaceQuickCmd extends AbstractExtension {

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

        @Schema(requiredMode = REQUIRED, description = "Command title", maxLength = 200)
        private String title;

        @Schema(requiredMode = REQUIRED, description = "Command text", maxLength = 5000)
        private String command;

        @Schema(description = "Command note", maxLength = 2000)
        private String note;

        @Schema(description = "Category such as Linux, PostgreSQL, Git, Odoo, Django", maxLength = 32)
        private String category;

        @Schema(description = "Tags, de-duplicated by the server")
        private List<String> tags = new ArrayList<>();

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

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags == null ? new ArrayList<>() : tags;
        }
    }
}
