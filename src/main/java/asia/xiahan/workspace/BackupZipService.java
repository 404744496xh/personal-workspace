package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.BackupImportResult;
import asia.xiahan.workspace.dto.BackupManifest;
import asia.xiahan.workspace.dto.BackupPayload;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.core.extension.attachment.Attachment;
import run.halo.app.core.extension.service.AttachmentService;
import run.halo.app.infra.ExternalUrlSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class BackupZipService {

    private static final Logger log = LoggerFactory.getLogger(BackupZipService.class);
    private static final String MANIFEST_ENTRY = "manifest.json";
    private static final String DATA_ENTRY = "data.ndjson";
    private static final String ATTACHMENT_DIR = "attachments/";
    private static final String ATTACHMENT_POLICY = "default-policy";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_ATTACHMENT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ATTACHMENTS = 2000;
    private static final int MAX_LINE_CHARS = 1024 * 1024;
    private static final long MAX_TOTAL_ATTACHMENT_BYTES = 1024L * 1024 * 1024;
    private static final Pattern UPLOAD_PATH =
        Pattern.compile("/upload/[^\\s()\\[\\]{}'\"<>]+");
    private static final Pattern SAFE_UPLOAD_PATH =
        Pattern.compile("/upload/[A-Za-z0-9._~%+@-]+");
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final DefaultDataBufferFactory BUFFER_FACTORY = new DefaultDataBufferFactory();
    private static final ProxySelector NO_PROXY = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException error) {
        }
    };

    private final WorkspaceDataAccess dataAccess;
    private final AttachmentService attachmentService;
    private final ExternalUrlSupplier externalUrlSupplier;
    private final Environment environment;
    private final Validator validator;
    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .proxy(NO_PROXY)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public BackupZipService(WorkspaceDataAccess dataAccess, AttachmentService attachmentService,
        ExternalUrlSupplier externalUrlSupplier, Environment environment, Validator validator) {
        this.dataAccess = dataAccess;
        this.attachmentService = attachmentService;
        this.externalUrlSupplier = externalUrlSupplier;
        this.environment = environment;
        this.validator = validator;
    }

    public Mono<Path> exportZip(String owner, URI requestUri) {
        return Mono.fromCallable(() -> exportZipBlocking(owner, requestUri))
            .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<BackupImportResult> importZip(String owner, Path zipPath, boolean replace) {
        return Mono.fromCallable(() -> prepareImport(owner, zipPath, replace))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(this::runImport);
    }

    private Path exportZipBlocking(String owner, URI requestUri) throws IOException {
        var temp = Files.createTempFile("personal-workspace-backup-", ".zip");
        temp.toFile().deleteOnExit();
        var attachmentPaths = new LinkedHashSet<String>();
        var counts = new LinkedHashMap<String, Integer>();
        try (var zip = new ZipOutputStream(Files.newOutputStream(temp),
            StandardCharsets.UTF_8);
             var writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry(DATA_ENTRY));
            int projectCount = writeLines(writer, "project",
                dataAccess.streamProjects(owner), dataAccess::toBackupProject);
            int todoCount = writeLines(writer, "todo",
                dataAccess.streamTodos(owner), dataAccess::toBackupTodo);
            int commandCount = writeLines(writer, "command",
                dataAccess.streamCommands(owner), dataAccess::toBackupCommand);
            int noteCount = 0;
            for (var note : dataAccess.streamNotes(owner).toIterable()) {
                var backup = dataAccess.toBackupNote(note);
                writeLine(writer, new BackupLine("note", JSON_MAPPER.valueToTree(backup)));
                collectAttachmentPaths(backup.content(), attachmentPaths);
                noteCount += 1;
            }
            writer.flush();
            zip.closeEntry();
            counts.put("projects", projectCount);
            counts.put("todos", todoCount);
            counts.put("commands", commandCount);
            counts.put("notes", noteCount);

            var attachments = new ArrayList<BackupManifest.BackupAttachment>();
            var warnings = new ArrayList<String>();
            var writtenHashes = new HashMap<String, String>();
            for (var path : attachmentPaths) {
                if (attachments.size() >= MAX_ATTACHMENTS) {
                    warnings.add("附件数量超过 " + MAX_ATTACHMENTS + "，其余未打包");
                    break;
                }
                FetchedAttachment fetched;
                try {
                    fetched = fetchAttachment(path, requestUri);
                } catch (Exception error) {
                    log.warn("Backup attachment fetch failed: {}", path, error);
                    warnings.add("附件下载失败 " + path);
                    continue;
                }
                if (fetched == null) {
                    warnings.add("附件不存在 " + path);
                    continue;
                }
                var existingPath = writtenHashes.get(fetched.sha256());
                var entryPath = existingPath;
                if (entryPath == null) {
                    entryPath = ATTACHMENT_DIR + fetched.sha256() + extensionOf(fetched.filename());
                    zip.putNextEntry(new ZipEntry(entryPath));
                    zip.write(fetched.bytes());
                    zip.closeEntry();
                    writtenHashes.put(fetched.sha256(), entryPath);
                }
                attachments.add(new BackupManifest.BackupAttachment(
                    entryPath,
                    path,
                    fetched.filename(),
                    fetched.mediaType(),
                    fetched.bytes().length,
                    fetched.sha256()));
            }

            var manifest = new BackupManifest(
                BackupManifest.FORMAT,
                BackupManifest.VERSION,
                Instant.now().toString(),
                baseUrl(requestUri),
                counts,
                attachments,
                warnings);
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY));
            zip.write(JSON_MAPPER.writeValueAsBytes(manifest));
            zip.closeEntry();
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temp);
            throw error;
        }
        return temp;
    }

    private <T> int writeLines(Writer writer, String type, Flux<T> source,
        java.util.function.Function<T, Object> mapper) throws IOException {
        int count = 0;
        for (var item : source.toIterable()) {
            writeLine(writer, new BackupLine(type, JSON_MAPPER.valueToTree(mapper.apply(item))));
            count += 1;
        }
        return count;
    }

    private static void writeLine(Writer writer, BackupLine line) throws IOException {
        writer.write(JSON_MAPPER.writeValueAsString(line));
        writer.write('\n');
    }

    private ImportPlan prepareImport(String owner, Path zipPath, boolean replace)
        throws IOException {
        try (var zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            var manifestBytes = readEntry(zip, MANIFEST_ENTRY, MAX_MANIFEST_BYTES);
            BackupManifest manifest;
            try {
                manifest = JSON_MAPPER.readValue(manifestBytes, BackupManifest.class);
            } catch (Exception error) {
                throw ApiException.badRequest("备份包 manifest 格式不正确");
            }
            if (manifest == null || !BackupManifest.FORMAT.equals(manifest.format())
                || !BackupManifest.VERSION.equals(manifest.version())) {
                throw ApiException.badRequest("备份包版本不支持");
            }

            var projects = new ArrayList<BackupPayload.BackupProject>();
            var todos = new ArrayList<BackupPayload.BackupTodo>();
            var commands = new ArrayList<BackupPayload.BackupCommand>();
            var notes = new ArrayList<BackupPayload.BackupNote>();
            readData(zip, projects, todos, commands, notes);
            var payload = new BackupPayload("1",
                manifest.exportedAt() == null ? Instant.now().toString() : manifest.exportedAt(),
                todos, projects, commands, notes);
            validate(payload);
            verifyCounts(manifest, projects.size(), todos.size(), commands.size(), notes.size());
            var attachments = nullSafe(manifest.attachments());
            var totalBytes = attachments.stream()
                .mapToLong(BackupManifest.BackupAttachment::size)
                .sum();
            if (totalBytes > MAX_TOTAL_ATTACHMENT_BYTES) {
                throw ApiException.payloadTooLarge();
            }
            return new ImportPlan(owner, zipPath, replace, payload, attachments,
                manifest.baseUrl());
        }
    }

    private Mono<BackupImportResult> runImport(ImportPlan plan) {
        var uploaded = new ArrayList<Attachment>();
        var pathToUrl = new HashMap<String, String>();
        var urlRewrites = new LinkedHashMap<String, String>();
        return Flux.fromIterable(plan.attachments())
            .concatMap(attachment -> {
                var path = attachment.path();
                if (path == null || path.isBlank()) {
                    return Mono.empty();
                }
                var existing = pathToUrl.get(path);
                if (existing != null) {
                    urlRewrites.put(attachment.url(), existing);
                    return Mono.empty();
                }
                return Mono.fromCallable(() -> readAttachment(plan.zipPath(), attachment))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(bytes -> uploadAttachment(attachment, bytes, uploaded))
                    .doOnNext(url -> {
                        pathToUrl.put(path, url);
                        urlRewrites.put(attachment.url(), url);
                    })
                    .then();
            })
            .then(Mono.defer(() -> {
                var rewrittenNotes = plan.payload().notes().stream()
                    .map(note -> rewriteNote(note, urlRewrites, plan.baseUrl()))
                    .toList();                var rewrittenPayload = new BackupPayload(plan.payload().version(),
                    plan.payload().exportedAt(), plan.payload().todos(), plan.payload().projects(),
                    plan.payload().commands(), rewrittenNotes);
                return dataAccess.importBackup(plan.owner(), rewrittenPayload, plan.replace());
            }))
            .onErrorResume(error -> Flux.fromIterable(uploaded)
                .concatMap(attachment -> attachmentService.delete(attachment)
                    .onErrorResume(ignored -> Mono.empty()))
                .then(Mono.error(error)));
    }

    private void readData(ZipFile zip, List<BackupPayload.BackupProject> projects,
        List<BackupPayload.BackupTodo> todos, List<BackupPayload.BackupCommand> commands,
        List<BackupPayload.BackupNote> notes) throws IOException {
        var entry = zip.getEntry(DATA_ENTRY);
        if (entry == null) {
            throw ApiException.badRequest("备份包缺少数据文件");
        }
        try (var reader = new BufferedReader(new InputStreamReader(zip.getInputStream(entry),
            StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber += 1;
                if (line.isBlank()) {
                    continue;
                }
                if (line.length() > MAX_LINE_CHARS) {
                    throw ApiException.badRequest("备份数据第 " + lineNumber + " 行过长");
                }
                BackupLine record;
                try {
                    record = JSON_MAPPER.readValue(line, BackupLine.class);
                } catch (Exception error) {
                    throw ApiException.badRequest("备份数据第 " + lineNumber + " 行格式不正确");
                }
                try {
                    switch (record.type()) {
                        case "project" -> projects.add(
                            JSON_MAPPER.treeToValue(record.data(), BackupPayload.BackupProject.class));
                        case "todo" -> todos.add(
                            JSON_MAPPER.treeToValue(record.data(), BackupPayload.BackupTodo.class));
                        case "command" -> commands.add(
                            JSON_MAPPER.treeToValue(record.data(), BackupPayload.BackupCommand.class));
                        case "note" -> notes.add(
                            JSON_MAPPER.treeToValue(record.data(), BackupPayload.BackupNote.class));
                        default -> throw ApiException.badRequest(
                            "备份数据第 " + lineNumber + " 行类型不支持");
                    }
                } catch (ApiException error) {
                    throw error;
                } catch (Exception error) {
                    throw ApiException.badRequest("备份数据第 " + lineNumber + " 行格式不正确");
                }
            }
        }
    }

    private void validate(BackupPayload payload) {
        Set<ConstraintViolation<BackupPayload>> violations = validator.validate(payload);
        if (violations.isEmpty()) {
            return;
        }
        throw ApiException.badRequest(violations.stream()
            .map(ConstraintViolation::getMessage)
            .distinct()
            .limit(3)
            .reduce((left, right) -> left + "；" + right)
            .orElse("备份数据校验失败"));
    }

    private void verifyCounts(BackupManifest manifest, int projects, int todos, int commands,
        int notes) {
        var counts = manifest.counts();
        if (counts == null) {
            return;
        }
        checkCount("projects", counts.get("projects"), projects);
        checkCount("todos", counts.get("todos"), todos);
        checkCount("commands", counts.get("commands"), commands);
        checkCount("notes", counts.get("notes"), notes);
    }

    private static void checkCount(String name, Integer expected, int actual) {
        if (expected != null && expected != actual) {
            throw ApiException.badRequest("备份包数据条数与 manifest 不一致：" + name);
        }
    }

    private byte[] readAttachment(Path zipPath, BackupManifest.BackupAttachment attachment)
        throws IOException {
        var path = attachment.path();
        if (!isSafeEntry(path)) {
            throw ApiException.badRequest("备份包附件路径不合法");
        }
        try (var zip = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            var bytes = readEntry(zip, path, MAX_ATTACHMENT_BYTES);
            if (attachment.sha256() != null && !attachment.sha256().isBlank()
                && !sha256(bytes).equalsIgnoreCase(attachment.sha256())) {
                throw ApiException.badRequest("附件校验失败：" + path);
            }
            return bytes;
        }
    }

    private Mono<String> uploadAttachment(BackupManifest.BackupAttachment attachment, byte[] bytes,
        List<Attachment> uploaded) {
        var path = attachment.path();
        var mediaType = parseMediaType(attachment.mediaType());
        var filename = sanitizeFilename(attachment.filename(), path, mediaType);
        return attachmentService.upload(ATTACHMENT_POLICY, null, filename,
                Flux.just(BUFFER_FACTORY.wrap(bytes)), mediaType)
            .switchIfEmpty(Mono.error(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "ATTACHMENT_UPLOAD_FAILED", "附件上传失败")))
            .flatMap(result -> {
                uploaded.add(result);
                return attachmentService.getPermalink(result)
                    .switchIfEmpty(Mono.error(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "ATTACHMENT_URL_MISSING", "附件已上传但无法获取访问地址")))
                    .map(URI::toString);
            });
    }

    private BackupPayload.BackupNote rewriteNote(BackupPayload.BackupNote note,
        Map<String, String> rewrites, String oldBaseUrl) {
        if (note.content() == null || note.content().isEmpty() || rewrites.isEmpty()) {
            return note;
        }
        var content = note.content();
        var oldBase = trimSlash(oldBaseUrl);
        var newBase = externalUrl();
        for (var entry : rewrites.entrySet()) {
            var oldUrl = entry.getKey();
            var newUrl = entry.getValue();
            if (oldUrl == null || oldUrl.isBlank() || newUrl == null || newUrl.isBlank()) {
                continue;
            }
            if (!oldBase.isEmpty()) {
                content = content.replace(oldBase + oldUrl, absoluteUrl(newBase, newUrl));
            }
            content = content.replace(oldUrl, newUrl);
        }
        if (content.equals(note.content())) {
            return note;
        }
        return new BackupPayload.BackupNote(note.title(), content, note.summary(), note.tags(),
            note.project(), note.module(), note.todos(), note.updatedAt(), note.post());
    }

    private void collectAttachmentPaths(String content, Set<String> paths) {
        if (content == null || content.isBlank()) {
            return;
        }
        var baseHost = hostOf(externalUrl());
        Matcher matcher = UPLOAD_PATH.matcher(content);
        while (matcher.find()) {
            var raw = trimUrlTail(matcher.group());
            String path;
            try {
                var uri = URI.create(raw);
                if (uri.isAbsolute()) {
                    if (!hostOf(raw).equalsIgnoreCase(baseHost)) {
                        continue;
                    }
                    path = uri.getPath();
                } else {
                    path = uri.getPath();
                }
            } catch (IllegalArgumentException error) {
                continue;
            }
            var queryIndex = path == null ? -1 : path.indexOf('?');
            if (queryIndex >= 0) {
                path = path.substring(0, queryIndex);
            }
            if (path == null || !SAFE_UPLOAD_PATH.matcher(path).matches()
                || path.contains("..")) {
                continue;
            }
            var lower = path.toLowerCase(Locale.ROOT);
            if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e%2e")) {
                continue;
            }
            paths.add(path);
        }
    }

    private FetchedAttachment fetchAttachment(String path, URI requestUri) throws Exception {
        Exception lastError = null;
        for (var base : candidateBases(requestUri)) {
            try {
                var uri = URI.create(trimSlash(base) + path);
                var request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
                var response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() != 200) {
                    lastError = new IOException("HTTP " + response.statusCode() + " from " + base);
                    continue;
                }
                var bytes = response.body();
                if (bytes == null || bytes.length == 0) {
                    return null;
                }
                if (bytes.length > MAX_ATTACHMENT_BYTES) {
                    throw new IOException("attachment too large");
                }
                var filename = filenameOf(path);
                var mediaType = response.headers().firstValue("Content-Type")
                    .map(BackupZipService::baseMediaType)
                    .orElseGet(() -> mediaTypeOf(filename));
                return new FetchedAttachment(bytes, filename, mediaType, sha256(bytes));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        return null;
    }

    private List<String> candidateBases(URI requestUri) {
        var bases = new LinkedHashSet<String>();
        var configuredPort = environment.getProperty("server.port");
        if (configuredPort != null && !configuredPort.isBlank()) {
            bases.add("http://127.0.0.1:" + configuredPort.trim());
        }
        if (requestUri != null && requestUri.getPort() > 0) {
            bases.add("http://127.0.0.1:" + requestUri.getPort());
        }
        bases.add("http://127.0.0.1:8090");
        if (requestUri != null && requestUri.getScheme() != null
            && requestUri.getAuthority() != null) {
            bases.add(requestUri.getScheme() + "://" + requestUri.getAuthority());
        }
        var external = externalUrl();
        if (!external.isBlank()) {
            bases.add(external);
        }
        return List.copyOf(bases);
    }

    private String baseUrl(URI requestUri) {
        var external = externalUrl();
        if (!external.isBlank()) {
            return external;
        }
        if (requestUri != null && requestUri.getScheme() != null
            && requestUri.getAuthority() != null) {
            return requestUri.getScheme() + "://" + requestUri.getAuthority();
        }
        return "";
    }

    private String externalUrl() {
        try {
            var uri = externalUrlSupplier.get();
            return uri == null ? "" : trimSlash(uri.toString());
        } catch (RuntimeException error) {
            return "";
        }
    }

    private static byte[] readEntry(ZipFile zip, String name, int maxBytes) throws IOException {
        var entry = zip.getEntry(name);
        if (entry == null) {
            throw ApiException.badRequest("备份包缺少文件：" + name);
        }
        if (entry.getSize() > maxBytes) {
            throw ApiException.payloadTooLarge();
        }
        try (InputStream input = zip.getInputStream(entry)) {
            var out = new ByteArrayOutputStream();
            var buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw ApiException.payloadTooLarge();
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean isSafeEntry(String path) {
        return path != null && path.startsWith(ATTACHMENT_DIR)
            && !path.contains("..") && !path.contains("\\");
    }

    private static MediaType parseMediaType(String value) {
        if (value == null || value.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(value);
        } catch (RuntimeException error) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static String baseMediaType(String value) {
        return value.split(";", 2)[0].trim();
    }

    private static String mediaTypeOf(String filename) {
        var lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF_VALUE;
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static String filenameOf(String path) {
        var name = path.substring(path.lastIndexOf('/') + 1);
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Keep the encoded name when it cannot be decoded.
        }
        return name.isBlank() ? "attachment" : name;
    }

    private static String sanitizeFilename(String filename, String path, MediaType mediaType) {
        var name = filename == null || filename.isBlank() ? filenameOf(path) : filename;
        name = name.replaceAll(".*[\\\\/]", "").replaceAll("[\\r\\n\\t]", "").trim();
        if (name.isBlank()) {
            name = "attachment-" + sha256(path.getBytes(StandardCharsets.UTF_8)) + extensionOf(path);
        } else if (extensionOf(name).isEmpty()) {
            var extension = switch (mediaType.getSubtype().toLowerCase(Locale.ROOT)) {
                case "png" -> ".png";
                case "jpeg", "jpg" -> ".jpg";
                case "gif" -> ".gif";
                case "webp" -> ".webp";
                case "svg+xml" -> ".svg";
                default -> "";
            };
            name += extension;
        }
        return name.length() > 120 ? name.substring(name.length() - 120) : name;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        var dot = filename.lastIndexOf('.');
        if (dot < 0 || dot < filename.length() - 10) {
            return "";
        }
        var extension = filename.substring(dot);
        return extension.matches("\\.[A-Za-z0-9]+") ? extension : "";
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String trimUrlTail(String value) {
        var end = value.length();
        while (end > 0 && ".,;:!?)]}".indexOf(value.charAt(end - 1)) >= 0) {
            end -= 1;
        }
        return value.substring(0, end);
    }

    private static String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String absoluteUrl(String base, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return trimSlash(base) + url;
    }

    private static String hostOf(String url) {
        try {
            var uri = URI.create(url);
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (IllegalArgumentException error) {
            return "";
        }
    }

    private static <T> List<T> nullSafe(List<T> value) {
        return value == null ? List.of() : value;
    }

    private record BackupLine(String type, JsonNode data) {
    }

    private record ImportPlan(String owner, Path zipPath, boolean replace, BackupPayload payload,
        List<BackupManifest.BackupAttachment> attachments, String baseUrl) {
    }

    private record FetchedAttachment(byte[] bytes, String filename, String mediaType,
        String sha256) {
    }
}
