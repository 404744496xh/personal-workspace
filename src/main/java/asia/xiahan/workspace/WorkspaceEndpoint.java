package asia.xiahan.workspace;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import asia.xiahan.workspace.dto.BackupPayload;
import asia.xiahan.workspace.dto.ImageUploadResponse;
import asia.xiahan.workspace.dto.ModuleRenameRequest;
import asia.xiahan.workspace.dto.NoteUpsertRequest;
import asia.xiahan.workspace.dto.NoteView;
import asia.xiahan.workspace.dto.ProjectUpsertRequest;
import asia.xiahan.workspace.dto.ProjectView;
import asia.xiahan.workspace.dto.QuickCmdUpsertRequest;
import asia.xiahan.workspace.dto.QuickCmdView;
import asia.xiahan.workspace.dto.SearchResponse;
import asia.xiahan.workspace.dto.TodoListResponse;
import asia.xiahan.workspace.dto.TodoQuery;
import asia.xiahan.workspace.dto.TodoUpsertRequest;
import asia.xiahan.workspace.dto.TodoView;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.Validator;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.extension.GroupVersion;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WorkspaceEndpoint implements CustomEndpoint {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceEndpoint.class);
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_NOTE_BODY = 256 * 1024;
    private static final int MAX_BACKUP_BODY = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final String ATTACHMENT_POLICY = "default-policy";
    private static final Pattern NAME_PATTERN =
        Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
    // Backup files evolve across versions; tolerate removed/extra keys so old exports still import.
    private static final JsonMapper BULK_MAPPER = JsonMapper.builder().build();

    private final CurrentUser currentUser;
    private final WorkspaceDataAccess dataAccess;
    private final Validator validator;
    private final AttachmentService attachmentService;

    public WorkspaceEndpoint(CurrentUser currentUser, WorkspaceDataAccess dataAccess,
        Validator validator, AttachmentService attachmentService) {
        this.currentUser = currentUser;
        this.dataAccess = dataAccess;
        this.validator = validator;
        this.attachmentService = attachmentService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/todos", this::listTodos)
            .GET("/todos/{name}", this::getTodo)
            .POST("/todos", this::createTodo)
            .PUT("/todos/{name}", this::updateTodo)
            .DELETE("/todos/{name}", this::deleteTodo)
            .GET("/projects", this::listProjects)
            .POST("/projects", this::createProject)
            .PUT("/projects/{name}", this::updateProject)
            .PUT("/projects/{name}/modules/rename", this::renameProjectModule)
            .DELETE("/projects/{name}", this::deleteProject)
            .GET("/commands", this::listCommands)
            .GET("/commands/{name}", this::getCommand)
            .POST("/commands", this::createCommand)
            .PUT("/commands/{name}", this::updateCommand)
            .DELETE("/commands/{name}", this::deleteCommand)
            .GET("/notes", this::listNotes)
            .GET("/notes/{name}", this::getNote)
            .POST("/notes", this::createNote)
            .PUT("/notes/{name}", this::updateNote)
            .DELETE("/notes/{name}", this::deleteNote)
            .POST("/images", this::uploadImage)
            .GET("/search", this::search)
            .GET("/backup", this::exportBackup)
            .POST("/backup", this::importBackup)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.workspace.halo.run", "v1alpha1");
    }

    private Mono<ServerResponse> listTodos(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var query = parseQuery(request);
            return dataAccess.listTodos(username, query)
                .map(result -> new TodoListResponse(
                    result.getItems().stream().map(TodoView::of).toList(),
                    result.getTotal(),
                    result.getPage(),
                    result.getSize()
                ))
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> getTodo(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            return dataAccess.getTodo(username, name)
                .map(TodoView::of)
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> createTodo(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username ->
            readBody(request, TodoUpsertRequest.class)
                .flatMap(body -> dataAccess.createTodo(username, body))
                .map(TodoView::of)
                .flatMap(WorkspaceEndpoint::okJson)));
    }

    private Mono<ServerResponse> updateTodo(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            return readBody(request, TodoUpsertRequest.class)
                .flatMap(body -> dataAccess.updateTodo(username, name, body))
                .map(TodoView::of)
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> deleteTodo(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            requireConfirm(request);
            return dataAccess.deleteTodo(username, name)
                .then(noContent());
        }));
    }

    private Mono<ServerResponse> listProjects(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username ->
            dataAccess.listProjects(username)
                .map(ProjectView::of)
                .collectList()
                .flatMap(WorkspaceEndpoint::okJson)));
    }

    private Mono<ServerResponse> createProject(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username ->
            readBody(request, ProjectUpsertRequest.class)
                .flatMap(body -> dataAccess.createProject(username, body))
                .map(ProjectView::of)
                .flatMap(WorkspaceEndpoint::okJson)));
    }

    private Mono<ServerResponse> updateProject(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            return readBody(request, ProjectUpsertRequest.class)
                .flatMap(body -> dataAccess.updateProject(username, name, body))
                .map(ProjectView::of)
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> deleteProject(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            requireConfirm(request);
            return dataAccess.deleteProject(username, name)
                .then(noContent());
        }));
    }

    private Mono<ServerResponse> renameProjectModule(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            return readBody(request, ModuleRenameRequest.class)
                .flatMap(body -> dataAccess.renameModule(username, name, body))
                .map(ProjectView::of)
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> listCommands(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username ->
            dataAccess.listCommands(username)
                .map(QuickCmdView::of)
                .collectList()
                .flatMap(WorkspaceEndpoint::okJson)));
    }

    private Mono<ServerResponse> getCommand(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            return dataAccess.getCommand(username, name)
                .map(QuickCmdView::of)
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> createCommand(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username ->
            readBody(request, QuickCmdUpsertRequest.class)
                .flatMap(body -> dataAccess.createCommand(username, body))
                .map(QuickCmdView::of)
                .flatMap(WorkspaceEndpoint::okJson)));
    }

    private Mono<ServerResponse> updateCommand(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            return readBody(request, QuickCmdUpsertRequest.class)
                .flatMap(body -> dataAccess.updateCommand(username, name, body))
                .map(QuickCmdView::of)
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> deleteCommand(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            requireConfirm(request);
            return dataAccess.deleteCommand(username, name)
                .then(noContent());
        }));
    }

    private Mono<ServerResponse> listNotes(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username ->
            dataAccess.listNotes(username)
                .map(NoteView::of)
                .collectList()
                .flatMap(WorkspaceEndpoint::okJson)));
    }

    private Mono<ServerResponse> getNote(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            return dataAccess.getNote(username, name)
                .map(NoteView::of)
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> createNote(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username ->
            readBody(request, NoteUpsertRequest.class, MAX_NOTE_BODY)
                .flatMap(body -> dataAccess.createNote(username, body))
                .map(NoteView::of)
                .flatMap(WorkspaceEndpoint::okJson)));
    }

    private Mono<ServerResponse> updateNote(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            return readBody(request, NoteUpsertRequest.class, MAX_NOTE_BODY)
                .flatMap(body -> dataAccess.updateNote(username, name, body))
                .map(NoteView::of)
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> deleteNote(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var name = requireValidName(request);
            requireConfirm(request);
            return dataAccess.deleteNote(username, name)
                .then(noContent());
        }));
    }

    private Mono<ServerResponse> uploadImage(ServerRequest request) {
        return handle(currentUser.requireUsername().then(Mono.defer(() ->
            request.multipartData()
                .flatMap(parts -> {
                    var part = parts.getFirst("file");
                    if (!(part instanceof FilePart filePart)) {
                        return Mono.error(ApiException.badRequest("请粘贴图片文件"));
                    }
                    var mediaType = resolveImageMediaType(filePart);
                    if (mediaType == null) {
                        return Mono.error(ApiException.badRequest("只支持粘贴图片"));
                    }
                    if (filePart.headers().getContentLength() > MAX_IMAGE_BYTES) {
                        return Mono.error(ApiException.payloadTooLarge());
                    }
                    var filename = sanitizeFilename(filePart.filename(), mediaType);
                    return attachmentService.upload(ATTACHMENT_POLICY, null, filename,
                            filePart.content(), mediaType)
                        .flatMap(attachment -> attachmentService.getPermalink(attachment)
                            .map(URI::toString)
                            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "IMAGE_URL_MISSING", "图片已上传但无法获取访问地址"))))
                        .map(ImageUploadResponse::new);
                })
                .flatMap(WorkspaceEndpoint::okJson))));
    }

    private static MediaType resolveImageMediaType(FilePart filePart) {
        var declared = filePart.headers().getContentType();
        if (declared != null && "image".equalsIgnoreCase(declared.getType())
            && declared.getSubtype() != null && !declared.getSubtype().isBlank()) {
            return declared;
        }
        var name = filePart.filename() == null
            ? "" : filePart.filename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (name.endsWith(".bmp")) {
            return MediaType.parseMediaType("image/bmp");
        }
        return null;
    }

    private static String sanitizeFilename(String original, MediaType mediaType) {
        var base = original == null ? "" : original.replaceAll(".*[\\\\/]", "").trim();
        if (base.isEmpty()) {
            base = "pasted-" + UUID.randomUUID() + "." + extensionOf(mediaType);
        }
        return base.length() > 120 ? base.substring(base.length() - 120) : base;
    }

    private static String extensionOf(MediaType mediaType) {
        var subtype = mediaType.getSubtype();
        return "jpeg".equalsIgnoreCase(subtype) ? "jpg" : subtype.toLowerCase(Locale.ROOT);
    }

    private Mono<ServerResponse> search(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var keyword = request.queryParam("q").orElse("").trim();
            if (keyword.length() > 100) {
                throw ApiException.badRequest("搜索关键词不能超过 100 个字符");
            }
            return dataAccess.search(username, keyword)
                .map(hits -> new SearchResponse(keyword, hits))
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private Mono<ServerResponse> exportBackup(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username ->
            dataAccess.exportBackup(username)
                .flatMap(WorkspaceEndpoint::okJson)));
    }

    private Mono<ServerResponse> importBackup(ServerRequest request) {
        return handle(currentUser.requireUsername().flatMap(username -> {
            var replace = "true".equalsIgnoreCase(
                request.queryParam("replace").orElse("false"));
            return readBody(request, BackupPayload.class, MAX_BACKUP_BODY, BULK_MAPPER)
                .flatMap(body -> dataAccess.importBackup(username, body, replace))
                .flatMap(WorkspaceEndpoint::okJson);
        }));
    }

    private <T> Mono<T> readBody(ServerRequest request, Class<T> type) {
        return readBody(request, type, MAX_BODY);
    }

    private <T> Mono<T> readBody(ServerRequest request, Class<T> type, int maxBytes) {
        return readBody(request, type, maxBytes, JSON_MAPPER);
    }

    private <T> Mono<T> readBody(ServerRequest request, Class<T> type, int maxBytes,
        JsonMapper mapper) {
        var contentLength = request.headers().firstHeader("Content-Length");
        if (contentLength != null && !contentLength.isBlank()) {
            try {
                if (Long.parseLong(contentLength.trim()) > maxBytes) {
                    return Mono.error(ApiException.payloadTooLarge());
                }
            } catch (NumberFormatException ignored) {
                // Fall through to the actual byte length check.
            }
        }
        return request.body(BodyExtractors.toDataBuffers())
            .as(buffers -> DataBufferUtils.join(buffers, maxBytes))
            .flatMap(buffer -> {
                var bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                return parseBody(bytes, type, maxBytes, mapper);
            })
            .switchIfEmpty(Mono.error(ApiException.badRequest("请求体为空")))
            .onErrorMap(DataBufferLimitException.class, error -> ApiException.payloadTooLarge());
    }

    private <T> Mono<T> parseBody(byte[] bytes, Class<T> type, int maxBytes, JsonMapper mapper) {
        if (bytes.length == 0) {
            return Mono.error(ApiException.badRequest("请求体为空"));
        }
        if (bytes.length > maxBytes) {
            return Mono.error(ApiException.payloadTooLarge());
        }
        T body;
        try {
            body = mapper.readValue(bytes, type);
        } catch (Exception error) {
            return Mono.error(ApiException.badRequest("请求体格式不正确"));
        }
        if (body == null) {
            return Mono.error(ApiException.badRequest("请求体格式不正确"));
        }
        validate(body);
        return Mono.just(body);
    }

    private void validate(Object body) {
        var errors = new BeanPropertyBindingResult(body, "body");
        validator.validate(body, errors);
        if (!errors.hasErrors()) {
            return;
        }
        var message = errors.getAllErrors().stream()
            .map(error -> {
                var text = error.getDefaultMessage();
                if (error instanceof FieldError fieldError) {
                    return fieldError.getField() + " " + text;
                }
                return text;
            })
            .filter(text -> text != null && !text.isBlank())
            .distinct()
            .limit(3)
            .collect(Collectors.joining("；"));
        throw ApiException.badRequest(message.isBlank() ? "参数校验失败" : message);
    }

    private static TodoQuery parseQuery(ServerRequest request) {
        var keyword = request.queryParam("q").orElse("").trim();
        if (keyword.length() > 100) {
            throw ApiException.badRequest("搜索关键词不能超过 100 个字符");
        }
        var status = request.queryParam("status").orElse("all").trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ALL", "OPEN", "DONE").contains(status)) {
            throw ApiException.badRequest("status 参数不合法");
        }
        var priority = request.queryParam("priority").orElse("").trim().toUpperCase(Locale.ROOT);
        if (!priority.isEmpty() && !Set.of("HIGH", "MEDIUM", "LOW").contains(priority)) {
            throw ApiException.badRequest("priority 参数不合法");
        }
        var page = intParam(request, "page", 1, 1, 10_000);
        var size = intParam(request, "size", 20, 1, 500);
        return new TodoQuery(keyword, status, priority, page, size);
    }

    private static int intParam(ServerRequest request, String name, int defaultValue, int min,
        int max) {
        var raw = request.queryParam(name).orElse(null);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            throw ApiException.badRequest(name + " 必须是整数");
        }
        if (value < min || value > max) {
            throw ApiException.badRequest(name + " 超出范围");
        }
        return value;
    }

    private static String requireValidName(ServerRequest request) {
        var name = request.pathVariable("name");
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw ApiException.notFound();
        }
        return name;
    }

    private static void requireConfirm(ServerRequest request) {
        if (!"true".equalsIgnoreCase(request.queryParam("confirm").orElse(""))) {
            throw ApiException.badRequest("删除操作需要 confirm=true 二次确认");
        }
    }

    private static Mono<ServerResponse> noContent() {
        return ServerResponse.noContent()
            .header("Cache-Control", "no-store")
            .build();
    }

    private static Mono<ServerResponse> handle(Mono<ServerResponse> action) {
        return action
            .onErrorResume(ApiException.class, error -> errorResponse(error.getStatus(),
                error.getCode(), error.getMessage()))
            .onErrorResume(error -> {
                log.error("Personal workspace API failed", error);
                return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                    "服务暂时不可用，请稍后重试");
            });
    }

    private static Mono<ServerResponse> okJson(Object body) {
        return ServerResponse.ok()
            .contentType(APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .bodyValue(body);
    }

    private static Mono<ServerResponse> errorResponse(HttpStatus status, String code,
        String message) {
        return ServerResponse.status(status)
            .contentType(APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .bodyValue(Map.of("error", Map.of(
                "code", code,
                "message", message == null ? "请求失败" : message
            )));
    }
}
