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
    kind = "WorkspaceProject",
    plural = "workspaceprojects",
    singular = "workspaceproject"
)
public class WorkspaceProject extends AbstractExtension {

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

        @Schema(requiredMode = REQUIRED, description = "Project title", maxLength = 200)
        private String title;

        @Schema(description = "Progress percentage, 0 to 100", minimum = "0", maximum = "100")
        private Integer progress;

        @Schema(description = "Project modules, ordered")
        private List<String> modules = new ArrayList<>();

        @Schema(description = "Project note", maxLength = 2000)
        private String note;

        @Schema(description = "Related links")
        private List<String> links = new ArrayList<>();

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

        public Integer getProgress() {
            return progress;
        }

        public void setProgress(Integer progress) {
            this.progress = progress;
        }

        public List<String> getModules() {
            return modules;
        }

        public void setModules(List<String> modules) {
            this.modules = modules == null ? new ArrayList<>() : modules;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public List<String> getLinks() {
            return links;
        }

        public void setLinks(List<String> links) {
            this.links = links == null ? new ArrayList<>() : links;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags == null ? new ArrayList<>() : tags;
        }
    }
}
