package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.AiImportRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// OpenAI 兼容的 Chat Completions 接口，Base URL / 模型 / Key 来自插件设置
@Component
public class AiImportService {

    private static final String SETTINGS_CONFIG_MAP = "personal-workspace-config";
    private static final Duration TIMEOUT = Duration.ofSeconds(120);
    private static final int MAX_ROWS = 200;
    private static final int MAX_PROJECTS_IN_PROMPT = 100;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final ReactiveExtensionClient client;
    private final WorkspaceDataAccess dataAccess;

    public AiImportService(ReactiveExtensionClient client, WorkspaceDataAccess dataAccess) {
        this.client = client;
        this.dataAccess = dataAccess;
    }

    public Mono<List<AiImportRow>> analyze(String owner, String text) {
        return settings()
            .flatMap(ai -> dataAccess.listProjects(owner)
                .take(MAX_PROJECTS_IN_PROMPT)
                .collectList()
                .flatMap(projects -> callModel(ai, buildPrompt(projects), text)))
            .map(this::parseRows)
            .timeout(TIMEOUT)
            .onErrorMap(TimeoutException.class,
                error -> new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                    "AI 识别超时，请减少行数或稍后重试"));
    }

    private Mono<AiSettings> settings() {
        return client.fetch(ConfigMap.class, SETTINGS_CONFIG_MAP)
            .mapNotNull(configMap -> configMap.getData() == null ? null : configMap.getData().get("ai"))
            .map(this::readSettings)
            .defaultIfEmpty(AiSettings.empty())
            .flatMap(ai -> ai.valid()
                ? Mono.just(ai)
                : Mono.error(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "请先在插件设置里配置 AI 接口（Base URL、模型、API Key）")));
    }

    private AiSettings readSettings(String json) {
        try {
            var node = JSON_MAPPER.readTree(json);
            if (node == null) return AiSettings.empty();
            return new AiSettings(
                textOf(node, "aiBaseUrl"),
                textOf(node, "aiModel"),
                textOf(node, "aiApiKey"));
        } catch (Exception error) {
            return AiSettings.empty();
        }
    }

    private static String textOf(JsonNode node, String key) {
        var value = node.get(key);
        return value == null || value.isNull() ? "" : value.asText().trim();
    }

    private Mono<String> callModel(AiSettings ai, String systemPrompt, String userText) {
        var url = chatCompletionsUrl(ai.baseUrl());
        var body = Map.of(
            "model", ai.model(),
            "temperature", 0,
            "stream", false,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userText)
            ));
        return WebClient.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
            .build()
            .post()
            .uri(url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + ai.apiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(errorBody -> new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR",
                    "AI 接口调用失败（" + response.statusCode().value() + "）："
                        + errorMessage(errorBody))))
            .bodyToMono(JsonNode.class)
            .map(this::readContent)
            .onErrorMap(IllegalStateException.class,
                error -> new ApiException(HttpStatus.BAD_GATEWAY, "AI_BAD_OUTPUT",
                    "AI 返回内容无法解析，请重试或改用规则识别"));
    }

    private static String chatCompletionsUrl(String baseUrl) {
        var base = baseUrl.trim().replaceAll("/+$", "");
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }

    private static String errorMessage(String body) {
        if (body == null || body.isBlank()) return "无返回内容";
        try {
            var node = JSON_MAPPER.readTree(body);
            var message = node == null ? null : node.at("/error/message");
            if (message != null && !message.isNull() && !message.asText().isBlank()) {
                return truncate(message.asText());
            }
        } catch (Exception ignored) {
            // Fall through to the raw body.
        }
        return truncate(body.trim());
    }

    private static String truncate(String value) {
        var text = value.replaceAll("\\s+", " ").trim();
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private String readContent(JsonNode node) {
        var content = node == null ? null : node.at("/choices/0/message/content");
        if (content == null || content.isNull() || content.asText().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_BAD_OUTPUT",
                "AI 返回内容为空，请重试或改用规则识别");
        }
        return content.asText();
    }

    private String buildPrompt(List<WorkspaceProject> projects) {
        var builder = new StringBuilder();
        builder.append("你是待办导入助手。请把用户粘贴的表格内容整理成待办清单。\n")
            .append("用户已有的项目和模块（归并同义写法时必须逐字使用下列名称）：\n");
        if (projects.isEmpty()) {
            builder.append("（暂无项目和模块）\n");
        }
        for (var project : projects) {
            var spec = project.getSpec();
            if (spec == null) continue;
            builder.append("- ").append(spec.getTitle());
            var modules = spec.getModules();
            if (modules != null && !modules.isEmpty()) {
                builder.append("：").append(String.join("、", modules));
            }
            builder.append('\n');
        }
        builder.append("""
            规则：
            1. 每行一条待办，不要合并或拆分用户内容，不要臆造。
            2. 项目、模块可以为空，空表示未分类 / 不分模块。
            3. 原文的项目或模块如果只是空格、大小写、标点、简称或同义写法不同（例如“工作台”“个人工作台项目”都指已有的“个人工作台”），归并到已有名称。
            4. 修正明显的列错位、重复表头、多余空行和分隔符问题。
            5. 只输出 JSON 数组，不要解释或 Markdown 代码块，格式：
            [{"项目":"项目名或空字符串","模块":"模块名或空字符串","待办":"待办标题"}]
            """);
        return builder.toString();
    }

    private List<AiImportRow> parseRows(String output) {
        var start = output.indexOf('[');
        var end = output.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw badOutput();
        }
        JsonNode array;
        try {
            array = JSON_MAPPER.readTree(output.substring(start, end + 1));
        } catch (Exception error) {
            throw badOutput();
        }
        if (array == null || !array.isArray()) {
            throw badOutput();
        }
        var rows = new ArrayList<AiImportRow>();
        for (var index = 0; index < array.size(); index += 1) {
            var item = array.get(index);
            var title = firstText(item, "待办", "待办标题", "title", "todo").trim();
            if (title.isEmpty()) continue;
            rows.add(new AiImportRow(
                firstText(item, "项目", "project").trim(),
                firstText(item, "模块", "module").trim(),
                title));
        }
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_BAD_OUTPUT",
                "AI 没有识别出待办，请检查粘贴内容");
        }
        if (rows.size() > MAX_ROWS) {
            throw ApiException.badRequest("AI 识别结果超过 " + MAX_ROWS + " 行，请分批导入");
        }
        return rows;
    }

    private static ApiException badOutput() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "AI_BAD_OUTPUT",
            "AI 返回内容无法解析，请重试或改用规则识别");
    }

    private static String firstText(JsonNode node, String... keys) {
        for (var key : keys) {
            var value = node.get(key);
            if (value != null && !value.isNull()) return value.asText();
        }
        return "";
    }

    private record AiSettings(String baseUrl, String model, String apiKey) {
        static AiSettings empty() {
            return new AiSettings("", "", "");
        }

        boolean valid() {
            return !baseUrl.isBlank() && !model.isBlank() && !apiKey.isBlank();
        }
    }
}
