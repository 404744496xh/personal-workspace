package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.AiImportRow;
import java.text.Normalizer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// OpenAI 兼容的 Chat Completions 接口，Base URL / 模型 / Key 来自插件设置
@Component
public class AiImportService {

    private static final String SETTINGS_CONFIG_MAP = "personal-workspace-config";
    // 站点经过 Cloudflare（100 秒上限），插件必须更早返回 JSON 错误，否则前端只能看到网关错误页
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_ROWS = 200;
    private static final int MAX_PROJECTS_IN_PROMPT = 100;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();
    private static final Logger log = LoggerFactory.getLogger(AiImportService.class);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final ReactiveExtensionClient client;
    private final WorkspaceDataAccess dataAccess;

    public AiImportService(ReactiveExtensionClient client, WorkspaceDataAccess dataAccess) {
        this.client = client;
        this.dataAccess = dataAccess;
    }

    public Mono<List<AiImportRow>> analyze(String owner, String text, String targetProject) {
        var started = System.nanoTime();
        log.info("AI import start: chars={}", text.length());
        return settings()
            .flatMap(ai -> dataAccess.listProjects(owner)
                .take(MAX_PROJECTS_IN_PROMPT)
                .collectList()
                .flatMap(projects -> resolveTarget(owner, targetProject, projects)
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .flatMap(target -> {
                        var resolved = target.orElse(null);
                        return callModel(ai, buildPrompt(projects, resolved), text)
                            .map(output -> parseRows(output, projects, resolved));
                    })))
            .timeout(TIMEOUT)
            .doOnNext(rows -> log.info("AI import done: chars={}, rows={}, elapsedMs={}",
                text.length(), rows.size(), elapsedMs(started)))
            .doOnError(error -> log.warn("AI import failed: chars={}, elapsedMs={}, error={}",
                text.length(), elapsedMs(started), error.toString()))
            .onErrorMap(TimeoutException.class,
                error -> new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                    "AI 识别超时（90 秒），请减少行数或稍后重试"));
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    // 项目列表在提示词里只取前 100 个；导入目标可能更晚创建，需要单独取出来
    private Mono<WorkspaceProject> resolveTarget(String owner, String targetProject,
        List<WorkspaceProject> projects) {
        if (targetProject == null || targetProject.isBlank()) return Mono.empty();
        var known = projects.stream()
            .filter(project -> project.getMetadata() != null
                && targetProject.equals(project.getMetadata().getName()))
            .findFirst();
        if (known.isPresent()) return Mono.just(known.get());
        return dataAccess.getProject(owner, targetProject)
            .onErrorResume(ApiException.class, error -> Mono.empty());
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
        return Mono.fromCallable(() -> callModelBlocking(ai, systemPrompt, userText))
            .subscribeOn(Schedulers.boundedElastic());
    }

    // JDK HttpClient 在容器内实测可正常访问模型接口；Reactor Netty 在该环境下会一直无响应
    private String callModelBlocking(AiSettings ai, String systemPrompt, String userText) {
        var url = chatCompletionsUrl(ai.baseUrl());
        var body = Map.of(
            "model", ai.model(),
            "temperature", 0,
            "stream", false,
            "messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userText)
            ));
        log.info("AI request: url={}, model={}, chars={}", url, ai.model(), userText.length());
        try {
            var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(80))
                .header("Authorization", "Bearer " + ai.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    JSON_MAPPER.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
            var response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            var status = response.statusCode();
            var responseBody = response.body();
            if (status >= 400) {
                log.warn("AI upstream error: status={}, body={}", status, truncate(responseBody));
                throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR",
                    "AI 接口调用失败（" + status + "）：" + errorMessage(responseBody));
            }
            return readContent(JSON_MAPPER.readTree(responseBody));
        } catch (ApiException error) {
            throw error;
        } catch (HttpTimeoutException error) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AI_TIMEOUT",
                "AI 接口响应超时，请减少行数或稍后重试");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR", "AI 接口调用被中断，请重试");
        } catch (Exception error) {
            log.warn("AI request failed: {}", error.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_ERROR",
                "AI 接口调用失败：" + (error.getMessage() == null ? "未知错误" : error.getMessage()));
        }
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

    private String buildPrompt(List<WorkspaceProject> projects, WorkspaceProject target) {
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
        if (target != null && target.getSpec() != null) {
            var spec = target.getSpec();
            builder.append("本次导入的目标项目：").append(spec.getTitle()).append("，项目统一输出这个名字。\n");
            var modules = spec.getModules();
            if (modules == null || modules.isEmpty()) {
                builder.append("该项目还没有模块：按待办所属的业务对象起 2–6 字的名词模块名，不要按动作或页面起名。\n");
            } else {
                builder.append("该项目的已有模块：").append(String.join("、", modules)).append("。\n")
                    .append("每条待办都必须归入其中最贴近的一个已有模块，逐字输出已有模块名；")
                    .append("只有整条待办属于这些模块都没覆盖的新业务分区时，才起新的 2–6 字名词模块名。\n");
            }
        }
        builder.append("""
            沿用判断（优先级最高）：
            - 上面列表是站点里已经存在的项目与模块。每输出一个项目 / 模块前，先把它和列表逐个比对；只要指的是同一个东西，就原样输出已有名称，不要保留自己的新写法。
            - 同义叫法、简称、多字少字、词序不同、全角半角、空格标点差异，以及是否带“管理 / 系统 / 平台 / 模块 / 功能”等通用后缀，都算同一个东西。例如已有「讲师库管理：讲师库」，输入里的「讲师管理 / 讲师」应输出「讲师库管理 / 讲师库」。
            - 导入不会创建新项目：项目只能输出上面列表里的已有名称。输入里的项目名和列表对不上、或看起来是新业务领域时，项目输出空字符串（未分类），不要自造项目名。
            概念定义：
            - 项目：一个产品或工作领域，通常是名词短语，例如“讲师库管理”“个人工作台”。
            - 模块：项目内的功能分区，例如“讲师库”“排课”，比项目范围小。
            - 待办：能独立看懂的一件事，通常由“对象 / 功能 + 具体问题或期望”组成，例如“新增内部讲师时讲师级别无法自定义”；不要只写“新增内部讲师”这种没有具体信息的泛化动作。
            - 详情：对这条待办 / bug 的补充说明或问题描述，例如“讲师级别没有办法自定义”；可以是空。
            - 姓名：提出 / 反馈这条待办或 bug 的人名，例如“张三”。
            识别规则：
            1. 列顺序完全不固定：项目、模块、待办、详情、创建时间、姓名可能出现在任意位置，也可能缺少某一列；不要按第几列来猜，必须逐列读内容判断角色。
            2. 判断依据：多行重复、范围较宽的名词短语通常是项目；范围更小、归属于项目的名词是模块；动词短语、可以执行的一件事是待办。表头可能缺失、错位或与内容不一致，以单元格内容为准。
            3. 状态、负责人、优先级、功能、页面、菜单、分类等额外列直接忽略；只有描述、详情、说明、备注、问题描述、需求描述、问题现象这类内容才作为详情。详情可以适度总结提炼、分点排版（如 1. 2. 3.），只要意思不变；不要照抄客套话、重复内容和无意义的口水话，也不要编造原文没有的信息。详情必须是对这条需求 / bug 的具体描述；如果某列只是一个功能名、页面名、模块名，或内容与模块相同，不要把它当详情，详情留空。明确没有用的列可以直接丢掉，不要为了填满字段把无关内容塞进项目 / 模块 / 待办 / 详情。
            4. 待办标题要能独立看懂：如果原文只有短的功能名 / 页面名，真正内容在问题描述 / 需求描述列，就把两者合并成一句具体的标题，提炼出核心现象或期望，可以整理得更通顺，不必逐字照抄，但不要改变意思，也不要把整段长文原样塞进标题；标题已经说清楚时，详情留空，详情只放标题之外的复现步骤、截图说明或延伸信息，不要重复标题。每行只输出一条待办，不要加序号、合并或拆分。从 Excel 复制时，单元格内的换行会用双引号把整个单元格包起来；引号内的换行属于同一个单元格，不要当成新的一行，也不要把外层双引号写进结果。
            5. 同一项目 / 模块的多种写法（空格、大小写、标点、简称、同义叫法，例如“讲师库管理”和“讲师管理”）必须归并为同一名称；能和已有项目 / 模块对上时，严格按上面的「沿用判断」输出已有名称原文。
            6. 模块是待办所属的业务分区（名词短语），必须填写，不能为空。优先归入上面同一项目已有的模块：只要已有模块覆盖这条待办的业务对象，就逐字输出已有模块名，例如已有「培训」「考试&调研」时，“培训机构”“编辑考试”“考试详情”“考试报名”应分别输出「培训」「考试&调研」「考试&调研」「考试&调研」。即使原文没有模块列、或模块列是空的，也必须根据「待办标题 + 详情」里的业务词判断属于哪个已有模块（如标题含“课程”就归入已有「课程」模块，含“分类树”就归入已有「分类树」模块），不得因此输出空字符串。不要用“编辑 / 新增 / 添加 / 删除 / 查看 / 详情 / 列表 / 页面 / 导出 / 导入 / 设置”等动作或页面词直接当模块名；待办是“编辑 X”或“X 详情”时，模块应是 X 所属的业务分区，而不是“编辑 X”本身。现有模块确实都不覆盖时才起新的 2–6 字名词模块名；同一项目同类待办使用同一个模块名。
            7. 不要把描述性长句当项目或模块，也不要把项目 / 模块名当成待办；不要因为列顺序不同就把项目和模块互换。
            8. 创建时间只在原文明确给出时输出，不要用当前时间补齐；明确写“创建时间 / 创建日期 / 添加时间 / 录入时间 / 提交时间 / 日期 / 提出日期 / 反馈日期 / 报告日期”的日期列作为创建时间，没有任何标注的日期在没有其它日期列时也按创建时间处理；截止日期、完成日期、更新日期不要当成创建时间。格式统一为“YYYY-MM-DD HH:mm”，只有日期时输出“YYYY-MM-DD”；没有年份的日期（例如“7月14日”）按当前年份 {{YEAR}} 补全；原文没有创建时间时输出空字符串。
            9. 只有明确是“姓名 / 提出人 / 反馈人 / 提单人 / 提交人 / 报告人 / 谁提的”的人名列才作为姓名；负责人、处理人、指派人不算姓名。姓名保留原文，不要自行补充部门或称谓；原文没有姓名列时输出空字符串。
            10. 长度限制：项目 ≤200 字、模块 ≤32 字、待办 ≤200 字、详情 ≤5000 字、姓名 ≤64 字；项目、详情、姓名可以为空，空表示未分类 / 没有详情 / 未知；模块必须给出，不能为空。
            11. 如果某行缺少姓名 / 日期 / 项目等列，看起来是上一行描述或分点的续行（例如以“2、”“如”“其次”开头），把它并入上一行对应的详情，不要单独生成一条待办。
            12. 输入超过 200 行时只输出前 200 行。
            13. 只输出 JSON 数组，不要解释或 Markdown 代码块，格式：
            [{"项目":"项目名或空字符串","模块":"模块名或空字符串","待办":"待办标题","详情":"详情或空字符串","创建时间":"创建时间或空字符串","姓名":"姓名或空字符串"}]
            示例 1（列顺序打乱，无表头）：
            输入：
            新增内部讲师	2026-11-10 16:46	讲师库管理	讲师库	张三
            输出：
            [{"项目":"讲师库管理","模块":"讲师库","待办":"新增内部讲师","详情":"","创建时间":"2026-11-10 16:46","姓名":"张三"}]
            示例 2（备注列在中间，时间列在开头，还有截止日期和负责人）：
            输入：
            2026/11/9 08:30	备注：先看现有分类	讲师库	调整归档页分类树	截止 2026-11-20	负责人：李四	个人工作台
            输出：
            [{"项目":"个人工作台","模块":"讲师库","待办":"调整归档页分类树","详情":"先看现有分类","创建时间":"2026-11-09 08:30","姓名":""}]
            示例 3（姓名、日期、项目、模块、功能、问题描述）：
            输入：
            林美凤	7月14日	讲师库管理	讲师库	新增内部讲师	讲师级别没有办法自定义
            输出：
            [{"项目":"讲师库管理","模块":"讲师库","待办":"新增内部讲师时讲师级别无法自定义","详情":"","创建时间":"{{YEAR}}-07-14","姓名":"林美凤"}]
            """);
        return builder.toString().replace("{{YEAR}}", String.valueOf(LocalDate.now().getYear()));
    }

    private List<AiImportRow> parseRows(String output, List<WorkspaceProject> projects,
        WorkspaceProject target) {
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
                firstText(item, "详情", "描述", "详细描述", "说明", "备注", "问题描述",
                    "detail", "description").trim(),
                normalizeCreatedAt(firstText(item, "创建时间", "创建日期", "createdAt", "created")),
                firstText(item, "姓名", "提出人", "反馈人", "提交人", "reporter", "name").trim()));
        }
        if (rows.isEmpty()) {
            // 分批识别时某些块可能只有表头或换行续行，按空块处理，由前端汇总后提示
            log.info("AI import returned no rows");
            return List.of();
        }
        if (rows.size() > MAX_ROWS) {
            throw ApiException.badRequest("AI 识别结果超过 " + MAX_ROWS + " 行，请分批导入");
        }
        return canonicalize(rows, projects, target);
    }

    // AI 可能输出近义写法；能对上现有项目/模块时改回库里的名字，避免导入后多建重复项
    private static List<AiImportRow> canonicalize(List<AiImportRow> rows,
        List<WorkspaceProject> projects, WorkspaceProject target) {
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
        var targetTitle = target == null || target.getSpec() == null
            ? null
            : target.getSpec().getTitle();
        // 目标项目可能不在提示词的前 100 个里，模块列表直接从目标项目构建
        var targetModuleMatcher = target == null || target.getSpec() == null
            ? null
            : new NameMatcher(target.getSpec().getModules(), true);
        var canonical = new ArrayList<AiImportRow>(rows.size());
        for (var row : rows) {
            var project = targetTitle != null ? targetTitle : projectMatcher.match(row.project());
            var module = row.module();
            var moduleMatcher = targetTitle != null ? targetModuleMatcher : modulesByProject.get(project);
            if (moduleMatcher != null && !module.isBlank()) {
                module = moduleMatcher.match(module);
            }
            if (moduleMatcher != null && module.isBlank()) {
                module = moduleMatcher.matchContent(row.title(), row.detail());
            }
            // 模型偶尔把功能名 / 模块名当详情，或把已并入标题的描述再写一遍，重复时直接丢掉
            var detail = row.detail();
            var detailKey = key(detail);
            if (!detailKey.isEmpty() && (detailKey.equals(key(module))
                || detailKey.equals(key(row.module()))
                || detailKey.equals(key(project)) || key(row.title()).contains(detailKey))) {
                detail = "";
            }
            canonical.add(new AiImportRow(project, module, row.title(), detail,
                row.createdAt(), row.reporter()));
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

    // 模型常把“编辑考试”“考试详情”整串当模块名；匹配前先剥掉这些动作/页面词
    private static final List<String> ACTION_WORDS = List.of(
        "编辑", "新增", "添加", "删除", "查看", "详情", "列表", "页面", "导出", "导入",
        "设置", "配置", "查询", "审核", "发布", "预览", "修改", "更新", "维护", "管理", "搜索");

    // 按内容补模块时跳过这些泛词，避免“列表/数据/问题”这类通用词误命中
    private static final Set<String> CONTENT_STOP_TERMS = Set.of(
        "列表", "页面", "详情", "数据", "问题", "功能", "优化", "调整", "增加", "修改", "编辑",
        "管理", "系统", "平台", "模块", "统计", "操作", "导出", "导入", "设置", "配置", "查询");

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

        // 模块允许包含关系、动作词剥离和业务词匹配：
        // 前端开发→前端、培训机构→培训、编辑考试 / 考试详情→考试&调研
        private String partial(String valueKey, String fallback) {
            var hit = containment(valueKey);
            if (hit != null) return hit.isEmpty() ? fallback : hit;
            var stripped = stripActions(valueKey);
            if (!stripped.equals(valueKey)) {
                var exact = byKey.get(stripped);
                if (exact == null) exact = byCore.get(coreKey(stripped));
                if (exact != null) return exact;
                hit = containment(stripped);
                if (hit != null) return hit.isEmpty() ? fallback : hit;
            }
            hit = termMatch(valueKey);
            if (hit == null) return fallback;
            return hit.isEmpty() ? fallback : hit;
        }

        // 返回 null=无匹配、""=多个现有名称都匹配（不猜）、其他=命中的现有名称
        private String containment(String valueKey) {
            String hit = null;
            for (var name : names) {
                var nameKey = key(name);
                var shorter = valueKey.length() <= nameKey.length() ? valueKey : nameKey;
                var longer = valueKey.length() <= nameKey.length() ? nameKey : valueKey;
                if (shorter.length() < 2 || !longer.contains(shorter)) continue;
                if (longer.length() - shorter.length() > 2) continue;
                if (hit != null && !hit.equals(name)) return "";
                hit = name;
            }
            return hit;
        }

        private String termMatch(String valueKey) {
            String hit = null;
            var best = 0;
            for (var name : names) {
                for (var term : termsOf(name)) {
                    if (!valueKey.contains(term)) continue;
                    if (term.length() > best) {
                        best = term.length();
                        hit = name;
                    } else if (term.length() == best && !name.equals(hit)) {
                        return "";
                    }
                }
            }
            return hit;
        }

        // 模型没给模块时，用标题 / 详情里的业务词补一个最贴近的已有模块
        String matchContent(String title, String detail) {
            var contentKey = key((title == null ? "" : title) + " " + (detail == null ? "" : detail));
            if (contentKey.isEmpty()) return "";
            String hit = null;
            var best = 0;
            for (var name : names) {
                var nameKey = key(name);
                var score = scoreContent(contentKey, nameKey);
                for (var term : termsOf(name)) {
                    score = Math.max(score, scoreContent(contentKey, term));
                }
                if (score == 0) continue;
                if (score > best) {
                    best = score;
                    hit = name;
                } else if (score == best && !name.equals(hit)) {
                    return "";
                }
            }
            return hit == null ? "" : hit;
        }

        private static int scoreContent(String contentKey, String term) {
            var candidates = List.of(term, coreKey(term));
            var score = 0;
            for (var candidate : candidates) {
                if (candidate.length() < 2 || CONTENT_STOP_TERMS.contains(candidate)) continue;
                if (contentKey.contains(candidate)) score = Math.max(score, candidate.length());
            }
            return score;
        }
    }

    private static String stripActions(String key) {
        var result = key;
        var changed = true;
        while (changed) {
            changed = false;
            for (var word : ACTION_WORDS) {
                if (result.length() - word.length() < 2) continue;
                if (result.startsWith(word)) {
                    result = result.substring(word.length());
                    changed = true;
                } else if (result.endsWith(word)) {
                    result = result.substring(0, result.length() - word.length());
                    changed = true;
                }
            }
        }
        return result;
    }

    // 模块名里的业务词，例如“考试&调研”拆出“考试”“调研”
    private static List<String> termsOf(String name) {
        var normalized = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        var terms = new ArrayList<String>();
        for (var part : normalized.split("[\\s\\p{P}\\p{S}]+")) {
            if (part.length() >= 2 && !terms.contains(part)) terms.add(part);
        }
        return terms;
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
        return normalizeYearlessDate(text);
    }

    // 模型可能原样返回“7月14日”；没有年份时按当年补全
    private static final Pattern YEARLESS_DATE = Pattern.compile(
        "^(\\d{1,2})月(\\d{1,2})日(?:\\s+(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?)?$");

    private static String normalizeYearlessDate(String text) {
        var matcher = YEARLESS_DATE.matcher(text);
        if (!matcher.matches()) return null;
        try {
            var date = LocalDate.of(LocalDate.now().getYear(),
                Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            var time = LocalTime.of(
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)),
                matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4)),
                matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5)));
            return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toString();
        } catch (DateTimeException error) {
            return null;
        }
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
