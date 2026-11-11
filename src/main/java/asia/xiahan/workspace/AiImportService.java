package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.AiImportRow;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
                .flatMap(projects -> callModel(ai, buildPrompt(projects), text)
                    .map(output -> parseRows(output, projects))))
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
            沿用判断（优先级最高）：
            - 上面列表是站点里已经存在的项目与模块。每输出一个项目 / 模块前，先把它和列表逐个比对；只要指的是同一个东西，就原样输出已有名称，不要保留自己的新写法。
            - 同义叫法、简称、多字少字、词序不同、全角半角、空格标点差异，以及是否带“管理 / 系统 / 平台 / 模块 / 功能”等通用后缀，都算同一个东西。例如已有「讲师库管理：讲师库」，输入里的「讲师管理 / 讲师」应输出「讲师库管理 / 讲师库」。
            - 只有确实是新的业务领域或新功能分区时才输出新名称；拿不准时也优先沿用已有名称，避免导入后多出重复项目。
            概念定义：
            - 项目：一个产品或工作领域，通常是名词短语，例如“讲师库管理”“个人工作台”。
            - 模块：项目内的功能分区，例如“讲师库”“排课”，比项目范围小。
            - 待办：可以执行的一件事，通常是动词短语，例如“新增内部讲师”。
            - 姓名：提出 / 反馈这条待办或 bug 的人名，例如“张三”。
            识别规则：
            1. 列顺序完全不固定：项目、模块、待办、创建时间、姓名可能出现在任意位置，也可能缺少某一列；不要按第几列来猜，必须逐列读内容判断角色。
            2. 判断依据：多行重复、范围较宽的名词短语通常是项目；范围更小、归属于项目的名词是模块；动词短语、可以执行的一件事是待办。表头可能缺失、错位或与内容不一致，以单元格内容为准。
            3. 描述、状态、负责人、优先级等额外列全部忽略；明确是“姓名 / 提出人 / 反馈人 / 提单人 / 提交人 / 报告人 / 谁提的”的人名列才作为姓名，负责人、处理人、指派人不算姓名。日期列只有明确是“创建时间 / 创建日期 / 添加时间 / 录入时间 / 提交时间”，或语义上确实是任务建立时间时，才作为创建时间；截止日期、完成日期、更新日期不要当成创建时间。
            4. 不要臆造待办：每行输出一条，保留原文动作，不要加序号、合并或拆分。
            5. 同一项目 / 模块的多种写法（空格、大小写、标点、简称、同义叫法，例如“讲师库管理”和“讲师管理”）必须归并为同一名称；能和已有项目 / 模块对上时，严格按上面的「沿用判断」输出已有名称原文。
            6. 若原文缺少模块列，可根据待办语义补一个简短模块名（2–8 个字的名称短语，例如“讲师库”）；同一项目的同类待办使用同一个模块名；无法判断时留空。
            7. 不要把描述性长句当项目或模块，也不要把项目 / 模块名当成待办；不要因为列顺序不同就把项目和模块互换。
            8. 创建时间只在原文明确给出时输出，不要用当前时间补齐；格式统一为“YYYY-MM-DD HH:mm”，只有日期时输出“YYYY-MM-DD”；原文没有创建时间时输出空字符串。
            9. 姓名保留原文，不要自行补充部门或称谓；原文没有姓名列时输出空字符串。
            10. 长度限制：项目 ≤200 字、模块 ≤32 字、待办 ≤200 字、姓名 ≤64 字；项目、模块、姓名可以为空，空表示未分类 / 不分模块 / 未知。
            11. 输入超过 200 行时只输出前 200 行。
            12. 只输出 JSON 数组，不要解释或 Markdown 代码块，格式：
            [{"项目":"项目名或空字符串","模块":"模块名或空字符串","待办":"待办标题","创建时间":"创建时间或空字符串","姓名":"姓名或空字符串"}]
            示例 1（列顺序打乱，无表头）：
            输入：
            新增内部讲师	2026-11-10 16:46	讲师库管理	讲师库	张三
            输出：
            [{"项目":"讲师库管理","模块":"讲师库","待办":"新增内部讲师","创建时间":"2026-11-10 16:46","姓名":"张三"}]
            示例 2（备注列在中间，时间列在开头，还有截止日期和负责人）：
            输入：
            2026/11/9 08:30	备注：先看现有分类	讲师库	调整归档页分类树	截止 2026-11-20	负责人：李四	个人工作台
            输出：
            [{"项目":"个人工作台","模块":"讲师库","待办":"调整归档页分类树","创建时间":"2026-11-09 08:30","姓名":""}]
            """);
        return builder.toString();
    }

    private List<AiImportRow> parseRows(String output, List<WorkspaceProject> projects) {
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
                title,
                normalizeCreatedAt(firstText(item, "创建时间", "创建日期", "createdAt", "created")),
                firstText(item, "姓名", "提出人", "反馈人", "提交人", "reporter", "name").trim()));
        }
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_BAD_OUTPUT",
                "AI 没有识别出待办，请检查粘贴内容");
        }
        if (rows.size() > MAX_ROWS) {
            throw ApiException.badRequest("AI 识别结果超过 " + MAX_ROWS + " 行，请分批导入");
        }
        return canonicalize(rows, projects);
    }

    // AI 可能输出近义写法；能对上现有项目/模块时改回库里的名字，避免导入后多建重复项
    private static List<AiImportRow> canonicalize(List<AiImportRow> rows,
        List<WorkspaceProject> projects) {
        var projectNames = new ArrayList<String>();
        var modulesByProject = new HashMap<String, NameMatcher>();
        for (var project : projects) {
            var spec = project.getSpec();
            if (spec == null || spec.getTitle() == null) continue;
            projectNames.add(spec.getTitle());
            var modules = spec.getModules();
            if (modules == null || modules.isEmpty()) continue;
            modulesByProject.put(spec.getTitle(), new NameMatcher(modules, true));
        }
        var projectMatcher = new NameMatcher(projectNames, false);
        var canonical = new ArrayList<AiImportRow>(rows.size());
        for (var row : rows) {
            var project = projectMatcher.match(row.project());
            var module = row.module();
            var moduleMatcher = modulesByProject.get(project);
            if (moduleMatcher != null && !module.isBlank()) {
                module = moduleMatcher.match(module);
            }
            canonical.add(new AiImportRow(project, module, row.title(), row.createdAt(), row.reporter()));
        }
        return canonical;
    }

    private static String key(String value) {
        if (value == null) return "";
        var normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        return normalized.replaceAll("[\\s\\p{P}]+", "").toLowerCase(Locale.ROOT);
    }

    private static final List<String> GENERIC_SUFFIXES =
        List.of("管理", "系统", "平台", "模块", "功能");

    // 去掉“管理/系统”等通用后缀后仍至少保留 2 个字，避免把短名称削没
    private static String coreKey(String key) {
        var result = key;
        var changed = true;
        while (changed) {
            changed = false;
            for (var suffix : GENERIC_SUFFIXES) {
                if (result.endsWith(suffix) && result.length() - suffix.length() >= 2) {
                    result = result.substring(0, result.length() - suffix.length());
                    changed = true;
                }
            }
        }
        return result;
    }

    private static void register(Map<String, String> map, Set<String> ambiguous,
        String key, String name) {
        if (key.isEmpty() || ambiguous.contains(key)) return;
        var previous = map.get(key);
        if (previous == null) {
            map.put(key, name);
        } else if (!previous.equals(name)) {
            // 同一个键对应多个现有名称时不猜，交给模型或用户处理
            map.remove(key);
            ambiguous.add(key);
        }
    }

    private static final class NameMatcher {
        private final List<String> names;
        private final Map<String, String> byKey = new HashMap<>();
        private final Map<String, String> byCore = new HashMap<>();
        private final Set<String> ambiguousKeys = new HashSet<>();
        private final Set<String> ambiguousCores = new HashSet<>();
        private final boolean allowPartial;

        NameMatcher(List<String> values, boolean allowPartial) {
            this.allowPartial = allowPartial;
            this.names = values == null ? List.of()
                : values.stream().filter(value -> value != null && !value.isBlank()).toList();
            for (var name : names) {
                var nameKey = key(name);
                if (nameKey.isEmpty()) continue;
                register(byKey, ambiguousKeys, nameKey, name);
                register(byCore, ambiguousCores, coreKey(nameKey), name);
            }
        }

        String match(String value) {
            if (value == null || value.isBlank()) return value;
            var valueKey = key(value);
            if (valueKey.isEmpty()) return value;
            var hit = byKey.get(valueKey);
            if (hit != null) return hit;
            hit = byCore.get(coreKey(valueKey));
            if (hit != null) return hit;
            return allowPartial ? partial(valueKey, value) : value;
        }

        // 模块允许一个字以内的包含关系，例如“前端”与“前端开发”
        private String partial(String valueKey, String fallback) {
            String hit = null;
            for (var name : names) {
                var nameKey = key(name);
                var shorter = valueKey.length() <= nameKey.length() ? valueKey : nameKey;
                var longer = valueKey.length() <= nameKey.length() ? nameKey : valueKey;
                if (shorter.length() < 2 || !longer.contains(shorter)) continue;
                if (longer.length() - shorter.length() > 2) continue;
                if (hit != null && !hit.equals(name)) return fallback;
                hit = name;
            }
            return hit == null ? fallback : hit;
        }
    }

    private static final List<DateTimeFormatter> AI_DATE_TIMES = List.of(
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss")
    );
    private static final List<DateTimeFormatter> AI_DATES = List.of(
        DateTimeFormatter.ofPattern("yyyy-M-d"),
        DateTimeFormatter.ofPattern("yyyy/M/d")
    );

    private static String normalizeCreatedAt(String value) {
        var text = value == null ? "" : value.trim();
        if (text.isEmpty()) return null;
        try {
            return OffsetDateTime.parse(text).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            // Fall through to the local formats the model may return.
        }
        for (var formatter : AI_DATE_TIMES) {
            try {
                return LocalDateTime.parse(text, formatter)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toString();
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
        }
        for (var formatter : AI_DATES) {
            try {
                return LocalDate.parse(text, formatter)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toString();
            } catch (DateTimeParseException ignored) {
                // Try the next format.
            }
        }
        return null;
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
