package asia.xiahan.workspace;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.GVK;

/**
 * Append-only change log used by incremental sync. Deletion is represented by a tombstone entry;
 * the business extension itself is still hard-deleted.
 */
@GVK(
    group = "workspace.halo.run",
    version = "v1alpha1",
    kind = "WorkspaceSyncLog",
    plural = "workspacesynclogs",
    singular = "workspacesynclog"
)
public class WorkspaceSyncLog extends AbstractExtension {

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

        @Schema(requiredMode = REQUIRED, description = "Monotonically increasing sync sequence")
        private long seq;

        @Schema(
            requiredMode = REQUIRED,
            description = "Extension kind of the change",
            allowableValues = {"todo", "project", "note"}
        )
        private String kind;

        @Schema(requiredMode = REQUIRED, description = "Name of the changed extension", maxLength = 64)
        private String name;

        @Schema(description = "Whether the extension was deleted (tombstone)")
        private boolean deleted;

        @Schema(description = "Change time in ISO-8601, written by the server", maxLength = 40)
        private String updatedAt;

        @Schema(description = "View record serialized as JSON; null for tombstone entries")
        private String payloadJson;

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public long getSeq() {
            return seq;
        }

        public void setSeq(long seq) {
            this.seq = seq;
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isDeleted() {
            return deleted;
        }

        public void setDeleted(boolean deleted) {
            this.deleted = deleted;
        }

        public String getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(String updatedAt) {
            this.updatedAt = updatedAt;
        }

        public String getPayloadJson() {
            return payloadJson;
        }

        public void setPayloadJson(String payloadJson) {
            this.payloadJson = payloadJson;
        }
    }
}
