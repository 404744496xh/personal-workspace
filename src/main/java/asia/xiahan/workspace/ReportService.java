package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.NoteSummary;
import asia.xiahan.workspace.dto.ReportRequest;
import asia.xiahan.workspace.dto.ReportResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

// 周期报告：后端聚合全部待办、项目与笔记，再交给同一个 OpenAI 兼容模型写成 Markdown
@Component
public class ReportService {

    private static final int MAX_PERIOD_DAYS = 366;
    private static final int MAX_DETAIL_CHARS = 200;
    private static final int MAX_NOTE_EXCERPT = 500;
    private static final int MAX_PROJECT_NOTE = 240;
    private static final int MAX_PROJECT_LINKS = 6;
    private static final int MAX_PROJECT_NOTES = 5;
    private static final int MAX_OPEN_ITEMS = 12;
    private static final Pattern REPORT_TITLE = Pattern.compile(
        "^(?:\\d{4} 年 \\d{1,2} 月工作月报|\\d{4}-\\d{2}-\\d{2} ~ \\d{4}-\\d{2}-\\d{2} 工作周报)$");

    private static final String SYSTEM_PROMPT = """
        你是「个人工作台」的工作汇报助手。请根据给出的真实数据，写一份专业、可直接归档的中文工作月报/周报正文。
        要求：
        1. 只使用提供的数据，不虚构项目、数字、进度或结论；数据不足时如实说明。
        2. 输出 Markdown 正文，不要输出一级标题（系统会添加），不要使用代码围栏。
        3. 建议结构：## 本周期概览、## 项目进展、## 重点完成、## 待办与风险、## 笔记沉淀、## 下周期建议；可按内容合并，但要层次清晰。
        4. 项目进展要覆盖仍有剩余工作或本周期有动向的项目，写清当前进度、模块、周期内产出与剩余工作；不要把待办逐条罗列成流水账。
        5. 概览中的数字必须与数据一致；没有完成项或笔记时如实说明，不要编造。
        6. 语气专业、具体、少套话，正文 600–1200 字。
        """;

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final WorkspaceDataAccess dataAccess;
    private final AiImportService ai;

    public ReportService(WorkspaceDataAccess dataAccess, AiImportService ai) {
        this.dataAccess = dataAccess;
        this.ai = ai;
    }

    public Mono<ReportResponse> generate(String owner, ReportRequest request) {
        var view = request == null || request.view() == null ? "" : request.view().trim();
        if (!view.equals("month") && !view.equals("week")) {
            throw ApiException.badRequest("view 只能是 month 或 week");
        }
        var start = parseDate(request.start(), "开始日期");
        var end = parseDate(request.end(), "结束日期");
        if (end.isBefore(start)) {
            throw ApiException.badRequest("结束日期不能早于开始日期");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_PERIOD_DAYS) {
            throw ApiException.badRequest("统计周期不能超过一年");
        }
        var title = titleOf(view, start, end);
        return Mono.zip(
                dataAccess.listAllTodos(owner).collectList(),
                dataAccess.listProjects(owner).collectList(),
                dataAccess.listNotes(owner).collectList())
            .flatMap(tuple -> {
                var prompt = buildPrompt(view, start, end,
                    tuple.getT1(), tuple.getT2(), tuple.getT3());
                return ai.chat(SYSTEM_PROMPT, prompt);
            })
            .map(raw -> new ReportResponse(title, compose(raw)));
    }

    private static LocalDate parseDate(String value, String label) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(label + "不能为空");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException error) {
            throw ApiException.badRequest(label + "格式应为 YYYY-MM-DD");
        }
    }

    private static String titleOf(String view, LocalDate start, LocalDate end) {
        if (view.equals("month")) {
            return start.getYear() + " 年 " + start.getMonthValue() + " 月工作月报";
        }
        return start + " ~ " + end + " 工作周报";
    }

    private String buildPrompt(String view, LocalDate start, LocalDate end,
        List<WorkspaceTodo> todos, List<WorkspaceProject> projects,
        List<WorkspaceNote> notes) {
        // 历史报告本身不作为素材，避免下一次报告引用上一次报告
        var sourceNotes = notes.stream()
            .filter(note -> note.getSpec() != null && !isGeneratedReport(note.getSpec().getTitle()))
            .toList();
        var projectTitles = new LinkedHashMap<String, String>();
        for (var project : projects) {
            if (project.getMetadata() == null || project.getSpec() == null) continue;
            projectTitles.put(project.getMetadata().getName(), project.getSpec().getTitle());
        }

        var created = new ArrayList<WorkspaceTodo>();
        var completed = new ArrayList<WorkspaceTodo>();
        var dismissed = new ArrayList<WorkspaceTodo>();
        for (var todo : todos) {
            if (todo.getSpec() == null) continue;
            if (inPeriod(createdDate(todo), start, end)) created.add(todo);
            if (inPeriod(dateOf(todo.getSpec().getCompletedAt()), start, end)) {
                completed.add(todo);
            }
            if (todo.getSpec().isDismissed()) {
                var dismissedDate = todo.getSpec().getDismissedAt() == null
                    ? createdDate(todo)
                    : dateOf(todo.getSpec().getDismissedAt());
                if (inPeriod(dismissedDate, start, end)) dismissed.add(todo);
            }
        }

        var open = todos.stream()
            .filter(todo -> todo.getSpec() != null
                && !todo.getSpec().isCompleted() && !todo.getSpec().isDismissed())
            .toList();
        var staleBefore = LocalDate.now(ZONE).minusDays(7);
        var stale = open.stream()
            .filter(todo -> {
                var date = createdDate(todo);
                return date != null && date.isBefore(staleBefore);
            })
            .toList();

        var createdNames = namesOf(created);
        var completedNames = namesOf(completed);
        var dismissedNames = namesOf(dismissed);
        var stats = new LinkedHashMap<String, ProjectStat>();
        for (var project : projects) {
            if (project.getMetadata() != null) {
                stats.put(project.getMetadata().getName(), new ProjectStat());
            }
        }
        for (var todo : todos) {
            var spec = todo.getSpec();
            if (spec == null || spec.getProject() == null) continue;
            var stat = stats.get(spec.getProject());
            if (stat == null) continue;
            stat.total += 1;
            if (!spec.isCompleted() && !spec.isDismissed()) stat.open += 1;
            var name = todo.getMetadata() == null ? "" : todo.getMetadata().getName();
            if (createdNames.contains(name)) stat.created += 1;
            if (completedNames.contains(name)) stat.completed += 1;
            if (dismissedNames.contains(name)) stat.dismissed += 1;
        }

        var notesByProject = sourceNotes.stream()
            .filter(note -> note.getSpec() != null && note.getSpec().getProject() != null)
            .collect(Collectors.groupingBy(note -> note.getSpec().getProject(),
                Collectors.mapping(note -> note.getSpec().getTitle(), Collectors.toList())));

        var builder = new StringBuilder();
        builder.append("统计周期：").append(start).append(" 至 ").append(end)
            .append("（").append(view.equals("month") ? "月报" : "周报")
            .append("，共 ").append(ChronoUnit.DAYS.between(start, end) + 1).append(" 天）\n")
            .append("生成日期：").append(LocalDate.now(ZONE)).append("\n")
            .append("统计口径：新增按创建时间；完成按完成时间；不用处理按标记时间（缺失时按创建时间）。\n")
            .append("数据规模：待办 ").append(todos.size()).append(" 条，项目 ")
            .append(projects.size()).append(" 个，笔记 ").append(sourceNotes.size()).append(" 篇。\n\n")
            .append("一、待办动态（周期内）\n");
        appendTodoGroup(builder, "新增", created, projectTitles);
        appendTodoGroup(builder, "完成", completed, projectTitles);
        appendTodoGroup(builder, "不用处理", dismissed, projectTitles);
        builder.append("当前未完成 ").append(open.size()).append(" 条，其中创建超过 7 天 ")
            .append(stale.size()).append(" 条。");
        if (open.isEmpty()) {
            builder.append("\n");
        } else {
            builder.append("按创建时间从早到晚的前 ").append(MAX_OPEN_ITEMS).append(" 条：\n");
            open.stream()
                .sorted(Comparator.comparing(ReportService::createdDate,
                    Comparator.nullsLast(Comparator.naturalOrder())))
                .limit(MAX_OPEN_ITEMS)
                .forEach(todo -> builder.append(todoLine(todo, projectTitles)).append('\n'));
        }

        builder.append("\n二、项目（全部属性）\n");
        if (projects.isEmpty()) {
            builder.append("（暂无项目）\n");
        } else {
            var ordered = new ArrayList<>(projects);
            ordered.sort(Comparator
                .comparingInt((WorkspaceProject project) -> progressOf(project) >= 100 ? 1 : 0)
                .thenComparing(Comparator.comparingInt(ReportService::progressOf).reversed()));
            var index = 1;
            for (var project : ordered) {
                if (project.getSpec() == null || project.getMetadata() == null) continue;
                appendProject(builder, index++, project,
                    stats.get(project.getMetadata().getName()),
                    notesByProject.get(project.getMetadata().getName()));
            }
        }

        builder.append("\n三、本周期笔记\n");
        var activeNotes = sourceNotes.stream()
            .filter(note -> note.getSpec() != null)
            .filter(note -> inPeriod(noteDate(note), start, end))
            .toList();
        if (activeNotes.isEmpty()) {
            builder.append("（本周期没有更新笔记）\n");
        } else {
            builder.append("本周期更新 ").append(activeNotes.size()).append(" 篇：\n");
            for (var note : activeNotes) {
                var spec = note.getSpec();
                var meta = new ArrayList<String>();
                if (spec.getProject() != null) {
                    meta.add(projectTitles.getOrDefault(spec.getProject(), "未分类"));
                    if (spec.getModule() != null && !spec.getModule().isBlank()) {
                        meta.add(spec.getModule());
                    }
                }
                var extras = new ArrayList<String>();
                if (spec.getTags() != null && !spec.getTags().isEmpty()) {
                    extras.add("标签：" + String.join("、", spec.getTags()));
                }
                if (spec.getTodos() != null && !spec.getTodos().isEmpty()) {
                    extras.add("关联待办 " + spec.getTodos().size() + " 条");
                }
                var date = noteDate(note);
                if (date != null) extras.add("更新于 " + date);
                builder.append("- 《").append(spec.getTitle()).append("》");
                if (!meta.isEmpty()) {
                    builder.append("（").append(String.join(" / ", meta)).append("）");
                }
                if (!extras.isEmpty()) {
                    builder.append("｜").append(String.join("；", extras));
                }
                builder.append('\n');
                var excerpt = NoteSummary.of(note).excerpt();
                if (!excerpt.isBlank()) {
                    builder.append("  摘要：").append(clip(excerpt, MAX_NOTE_EXCERPT)).append('\n');
                }
            }
        }

        builder.append("\n请据此撰写报告。");
        return builder.toString();
    }

    private static void appendTodoGroup(StringBuilder builder, String label,
        List<WorkspaceTodo> todos, Map<String, String> projectTitles) {
        builder.append(label).append(' ').append(todos.size()).append(" 条：");
        if (todos.isEmpty()) {
            builder.append("（无）\n");
            return;
        }
        builder.append('\n');
        for (var todo : todos) {
            builder.append(todoLine(todo, projectTitles)).append('\n');
        }
    }

    private static void appendProject(StringBuilder builder, int index,
        WorkspaceProject project, ProjectStat stat, List<String> noteTitles) {
        var spec = project.getSpec();
        var progress = progressOf(project);
        builder.append(index).append(". ").append(spec.getTitle())
            .append("｜").append(progress >= 100 ? "已完结" : "进行中")
            .append("｜进度 ").append(progress).append("%")
            .append("｜模块：").append(joinOr(spec.getModules(), "无"))
            .append("｜标签：").append(joinOr(spec.getTags(), "无"));
        if (spec.getLinks() != null && !spec.getLinks().isEmpty()) {
            var links = spec.getLinks();
            var shown = String.join("、", links.subList(0, Math.min(MAX_PROJECT_LINKS, links.size())));
            builder.append("｜链接：").append(shown);
            if (links.size() > MAX_PROJECT_LINKS) {
                builder.append(" 等 ").append(links.size()).append(" 个");
            }
        }
        if (spec.getNote() != null && !spec.getNote().isBlank()) {
            builder.append("｜说明：").append(clip(spec.getNote(), MAX_PROJECT_NOTE));
        }
        builder.append('\n');
        if (stat != null) {
            builder.append("   待办：共 ").append(stat.total).append(" 条，未完成 ")
                .append(stat.open).append(" 条；本周期新增 ").append(stat.created)
                .append("、完成 ").append(stat.completed)
                .append("、不用处理 ").append(stat.dismissed).append(" 条。");
        }
        if (noteTitles != null && !noteTitles.isEmpty()) {
            var shown = noteTitles.subList(0, Math.min(MAX_PROJECT_NOTES, noteTitles.size()));
            builder.append("关联笔记：").append(String.join("、", shown));
            if (noteTitles.size() > MAX_PROJECT_NOTES) {
                builder.append(" 等 ").append(noteTitles.size()).append(" 篇");
            }
        } else {
            builder.append("关联笔记：无");
        }
        builder.append('\n');
    }

    private static String todoLine(WorkspaceTodo todo, Map<String, String> projectTitles) {
        var spec = todo.getSpec();
        var where = new ArrayList<String>();
        if (spec.getProject() != null && !spec.getProject().isBlank()) {
            where.add(projectTitles.getOrDefault(spec.getProject(), "未分类"));
            if (spec.getModule() != null && !spec.getModule().isBlank()) {
                where.add(spec.getModule());
            }
        } else {
            where.add("未分类");
        }
        var extras = new ArrayList<String>();
        if ("HIGH".equals(spec.getPriority())) extras.add("高优先级");
        if (spec.getReporter() != null && !spec.getReporter().isBlank()) {
            extras.add("提出人 " + spec.getReporter());
        }
        if (spec.getDueDate() != null && !spec.getDueDate().isBlank()) {
            extras.add("截止 " + spec.getDueDate());
        }
        if (spec.getDetail() != null && !spec.getDetail().isBlank()) {
            extras.add("详情：" + clip(spec.getDetail(), MAX_DETAIL_CHARS));
        }
        var line = new StringBuilder("- [").append(String.join(" / ", where)).append("] ")
            .append(spec.getTitle());
        if (!extras.isEmpty()) {
            line.append("（").append(String.join("；", extras)).append("）");
        }
        return line.toString();
    }

    private static Set<String> namesOf(List<WorkspaceTodo> todos) {
        return todos.stream()
            .filter(todo -> todo.getMetadata() != null)
            .map(todo -> todo.getMetadata().getName())
            .collect(Collectors.toSet());
    }

    private static int progressOf(WorkspaceProject project) {
        var spec = project.getSpec();
        return spec == null || spec.getProgress() == null ? 0 : spec.getProgress();
    }

    private static String joinOr(List<String> values, String fallback) {
        return values == null || values.isEmpty() ? fallback : String.join("、", values);
    }

    private static String clip(String value, int max) {
        var text = value.replaceAll("\\s+", " ").trim();
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static boolean isGeneratedReport(String title) {
        return title != null && REPORT_TITLE.matcher(title.trim()).matches();
    }

    private static boolean inPeriod(LocalDate date, LocalDate start, LocalDate end) {
        return date != null && !date.isBefore(start) && !date.isAfter(end);
    }

    private static LocalDate createdDate(WorkspaceTodo todo) {
        var date = dateOf(todo.getSpec() == null ? null : todo.getSpec().getCreatedAt());
        if (date == null && todo.getMetadata() != null
            && todo.getMetadata().getCreationTimestamp() != null) {
            date = todo.getMetadata().getCreationTimestamp().atZone(ZONE).toLocalDate();
        }
        return date;
    }

    private static LocalDate noteDate(WorkspaceNote note) {
        var date = dateOf(note.getSpec() == null ? null : note.getSpec().getUpdatedAt());
        if (date == null && note.getMetadata() != null
            && note.getMetadata().getCreationTimestamp() != null) {
            date = note.getMetadata().getCreationTimestamp().atZone(ZONE).toLocalDate();
        }
        return date;
    }

    private static LocalDate dateOf(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim()).atZone(ZONE).toLocalDate();
        } catch (Exception error) {
            return null;
        }
    }

    private static String compose(String raw) {
        var body = raw == null ? "" : raw.trim();
        if (body.startsWith("```")) {
            var newline = body.indexOf('\n');
            if (newline >= 0) body = body.substring(newline + 1).trim();
            if (body.endsWith("```")) {
                body = body.substring(0, body.length() - 3).trim();
            }
        }
        if (body.startsWith("# ")) {
            var newline = body.indexOf('\n');
            body = newline < 0 ? "" : body.substring(newline + 1).trim();
        }
        if (body.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_BAD_OUTPUT",
                "AI 返回内容为空，请重试");
        }
        return body + "\n";
    }

    private static final class ProjectStat {
        private int total;
        private int open;
        private int created;
        private int completed;
        private int dismissed;
    }
}
