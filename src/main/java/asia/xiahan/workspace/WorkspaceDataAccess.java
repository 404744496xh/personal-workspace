package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.BackupImportResult;
import asia.xiahan.workspace.dto.BackupPayload;
import asia.xiahan.workspace.dto.ChangeEntry;
import asia.xiahan.workspace.dto.ChangesResponse;
import asia.xiahan.workspace.dto.ModuleRenameRequest;
import asia.xiahan.workspace.dto.NoteUpsertRequest;
import asia.xiahan.workspace.dto.NoteView;
import asia.xiahan.workspace.dto.ProjectUpsertRequest;
import asia.xiahan.workspace.dto.ProjectView;
import asia.xiahan.workspace.dto.QuickCmdUpsertRequest;
import asia.xiahan.workspace.dto.SearchHit;
import asia.xiahan.workspace.dto.TodoQuery;
import asia.xiahan.workspace.dto.TodoUpsertRequest;
import asia.xiahan.workspace.dto.TodoView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.index.query.Condition;
import run.halo.app.extension.index.query.Queries;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class WorkspaceDataAccess {

    private static final Sort TODO_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final Sort PROJECT_SORT =
        Sort.by(Sort.Order.asc("metadata.creationTimestamp"));
    private static final Sort CREATED_DESC =
        Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final int SEARCH_LIMIT_PER_TYPE = 8;
    private static final Pattern POST_NAME =
        Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?");

    private static final java.util.concurrent.atomic.AtomicLong LAST_SYNC_SEQ =
        new java.util.concurrent.atomic.AtomicLong(0);
    private static final JsonMapper SYNC_JSON = JsonMapper.builder().build();
    private static final int SYNC_LOG_KEEP = 2000;
    private static final int SYNC_LOG_TRIM_BATCH = 200;

    private static long nextSyncSeq() {
        return LAST_SYNC_SEQ.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));
    }

    private final ReactiveExtensionClient client;

    public WorkspaceDataAccess(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<ListResult<WorkspaceTodo>> listTodos(String owner, TodoQuery query) {
        var condition = Queries.equal("spec.owner", owner);
        if (!query.keyword().isEmpty()) {
            condition = condition.and(Queries.or(
                Queries.contains("spec.title", query.keyword()),
                Queries.contains("spec.detail", query.keyword()),
                Queries.contains("spec.reporter", query.keyword())
            ));
        }
        if ("OPEN".equals(query.status())) {
            condition = condition.and(Queries.equal("spec.completed", false))
                .and(Queries.equal("spec.dismissed", false));
        } else if ("DONE".equals(query.status())) {
            condition = condition.and(Queries.equal("spec.completed", true));
        } else if ("DISMISSED".equals(query.status())) {
            condition = condition.and(Queries.equal("spec.dismissed", true));
        }
        if (!query.priority().isEmpty()) {
            condition = condition.and(Queries.equal("spec.priority", query.priority()));
        }
        return listTodoBy(condition, PageRequestImpl.of(query.page(), query.size(), TODO_SORT));
    }

    public Flux<WorkspaceTodo> listAllTodos(String owner) {
        return listOwned(WorkspaceTodo.class, owner, TODO_SORT);
    }

    public Mono<WorkspaceTodo> getTodo(String owner, String name) {
        return client.fetch(WorkspaceTodo.class, name)
            .filter(todo -> todo.getSpec() != null && owner.equals(todo.getSpec().getOwner()))
            .switchIfEmpty(Mono.error(ApiException.notFound()));
    }

    public Mono<WorkspaceTodo> createTodo(String owner, TodoUpsertRequest request) {
        return lookupProject(owner, request.project())
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(project -> {
                var todo = new WorkspaceTodo();
                todo.setMetadata(newMetadata());
                var spec = new WorkspaceTodo.Spec();
                apply(spec, owner, request, true);
                project.ifPresent(found -> validateTodoModule(spec, found));
                spec.setProjectStatus(project.map(WorkspaceDataAccess::statusOf).orElse(null));
                todo.setSpec(spec);
                return client.create(todo);
            })
            .flatMap(todo -> appendChange(owner, "todo", todo.getMetadata().getName(), false,
                Instant.now().toString(), serialize(TodoView.of(todo))).thenReturn(todo));
    }

    public Mono<WorkspaceTodo> updateTodo(String owner, String name, TodoUpsertRequest request) {
        return getTodo(owner, name)
            .flatMap(todo -> {
                var wasCompleted = todo.getSpec().isCompleted();
                return lookupProject(owner, request.project())
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .map(project -> {
                        var previousProject = todo.getSpec().getProject();
                        apply(todo.getSpec(), owner, request, false);
                        validateTodoModule(todo.getSpec(), project.orElse(null));
                        var spec = todo.getSpec();
                        if (spec.getProject() == null) {
                            spec.setProjectStatus(null);
                        } else if (!Objects.equals(previousProject, spec.getProject())
                            || spec.getProjectStatus() == null) {
                            project.ifPresent(found -> spec.setProjectStatus(statusOf(found)));
                        }
                        return todo;
                    })
                    .flatMap(updated -> {
                        var shouldRepeat = !wasCompleted && updated.getSpec().isCompleted()
                            && isRecurring(updated.getSpec());
                        return client.update(updated)
                            .flatMap(saved -> (shouldRepeat
                                ? createNextOccurrence(owner, saved)
                                : Mono.<WorkspaceTodo>empty())
                                .then(appendChange(owner, "todo",
                                    saved.getMetadata().getName(), false,
                                    Instant.now().toString(),
                                    serialize(TodoView.of(saved))).thenReturn(saved)));
                    });
            })
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    private static boolean isRecurring(WorkspaceTodo.Spec spec) {
        return spec != null && spec.getRecurrence() != null
            && !"NONE".equals(spec.getRecurrence());
    }

    private Mono<WorkspaceTodo> createNextOccurrence(String owner, WorkspaceTodo source) {
        var spec = source.getSpec();
        if (!isRecurring(spec) || spec.getDueDate() == null) {
            return Mono.empty();
        }
        var nextDueDate = nextDueDate(spec.getDueDate(), spec.getRecurrence());
        var seriesId = spec.getSeriesId();
        if (nextDueDate == null || seriesId == null) {
            return Mono.empty();
        }
        return listOwned(WorkspaceTodo.class, owner, TODO_SORT)
            .filter(todo -> todo.getSpec() != null
                && seriesId.equals(todo.getSpec().getSeriesId())
                && nextDueDate.equals(todo.getSpec().getDueDate()))
            .hasElements()
            .flatMap(exists -> {
                if (exists) {
                    return Mono.empty();
                }
                return lookupProject(owner, spec.getProject())
                    .map(Optional::of)
                    .defaultIfEmpty(Optional.empty())
                    .map(project -> {
                        var next = new WorkspaceTodo();
                        next.setMetadata(newMetadata());
                        var nextSpec = new WorkspaceTodo.Spec();
                        nextSpec.setOwner(owner);
                        nextSpec.setTitle(spec.getTitle());
                        nextSpec.setDetail(spec.getDetail());
                        nextSpec.setPriority(spec.getPriority());
                        nextSpec.setTags(spec.getTags() == null
                            ? new ArrayList<>() : new ArrayList<>(spec.getTags()));
                        nextSpec.setProject(spec.getProject());
                        nextSpec.setModule(spec.getModule());
                        nextSpec.setProjectStatus(project.map(WorkspaceDataAccess::statusOf)
                            .orElse(null));
                        nextSpec.setReporter(spec.getReporter());
                        nextSpec.setDueDate(nextDueDate);
                        nextSpec.setCompleted(false);
                        nextSpec.setCompletedAt(null);
                        nextSpec.setDismissed(false);
                        nextSpec.setDismissedAt(null);
                        nextSpec.setPinned(false);
                        nextSpec.setRecurrence(spec.getRecurrence());
                        nextSpec.setSeriesId(seriesId);
                        nextSpec.setCreatedAt(Instant.now().toString());
                        next.setSpec(nextSpec);
                        return next;
                    })
                    .flatMap(client::create)
                    .flatMap(next -> appendChange(owner, "todo",
                        next.getMetadata().getName(), false, Instant.now().toString(),
                        serialize(TodoView.of(next))).thenReturn(next));
            });
    }

    private static String nextDueDate(String dueDate, String recurrence) {
        try {
            var next = LocalDate.parse(dueDate);
            var today = LocalDate.now(ZoneId.systemDefault());
            do {
                next = switch (recurrence) {
                    case "DAILY" -> next.plusDays(1);
                    case "WEEKLY" -> next.plusWeeks(1);
                    case "MONTHLY" -> next.plusMonths(1);
                    default -> null;
                };
                if (next == null) {
                    return null;
                }
            } while (!next.isAfter(today));
            return next.toString();
        } catch (DateTimeParseException error) {
            return null;
        }
    }

    public Mono<Void> deleteTodo(String owner, String name) {
        return getTodo(owner, name)
            .flatMap(todo -> client.delete(todo).thenReturn(todo))
            .flatMap(todo -> removeTodoFromNotes(owner, todo.getMetadata().getName())
                .then(appendChange(owner, "todo", todo.getMetadata().getName(), true,
                    Instant.now().toString(), null)))
            .then();
    }

    private Mono<Void> removeTodoFromNotes(String owner, String todoName) {
        return listOwned(WorkspaceNote.class, owner, Sort.unsorted())
            .filter(note -> note.getSpec() != null
                && note.getSpec().getTodos() != null
                && note.getSpec().getTodos().contains(todoName))
            .flatMap(note -> {
                var remaining = new ArrayList<>(note.getSpec().getTodos());
                remaining.removeIf(todoName::equals);
                note.getSpec().setTodos(remaining);
                return client.update(note)
                    .flatMap(saved -> appendChange(owner, "note", saved.getMetadata().getName(),
                        false, Instant.now().toString(), serialize(NoteView.of(saved))))
                    .then();
            })
            .then();
    }

    public Flux<WorkspaceProject> listProjects(String owner) {
        return listOwned(WorkspaceProject.class, owner, PROJECT_SORT);
    }

    public Mono<WorkspaceProject> getProject(String owner, String name) {
        return client.fetch(WorkspaceProject.class, name)
            .filter(project -> project.getSpec() != null
                && owner.equals(project.getSpec().getOwner()))
            .switchIfEmpty(Mono.error(ApiException.notFound()));
    }

    public Mono<WorkspaceProject> createProject(String owner, ProjectUpsertRequest request) {
        return Mono.fromSupplier(() -> buildProject(owner, request.title(), request.modules(),
                request.status(), request.note(), request.links(), request.tags()))
            .flatMap(client::create)
            .flatMap(project -> appendChange(owner, "project", project.getMetadata().getName(),
                false, Instant.now().toString(), serialize(ProjectView.of(project)))
                .thenReturn(project));
    }

    public Mono<WorkspaceProject> updateProject(String owner, String name,
        ProjectUpsertRequest request) {
        return getProject(owner, name)
            .flatMap(project -> {
                var spec = project.getSpec();
                spec.setTitle(request.title().trim());
                var previousModules = new LinkedHashSet<>(nullSafe(spec.getModules()));
                var nextModules = normalizeModules(request.modules());
                spec.setModules(nextModules);
                if (request.statusHistory() != null) {
                    var history = normalizeStatusHistory(request.statusHistory());
                    var lastStatus = history.get(history.size() - 1).getStatus();
                    if (request.status() != null && !lastStatus.equals(request.status())) {
                        throw ApiException.badRequest("状态时间线与当前状态不一致");
                    }
                    spec.setStatusHistory(history);
                    spec.setStatus(lastStatus);
                } else if (request.status() != null) {
                    applyStatus(project, request.status());
                } else {
                    spec.setStatusHistory(ensureStatusHistory(project));
                }
                if (request.note() != null) {
                    spec.setNote(trimToNull(request.note()));
                }
                if (request.links() != null) {
                    spec.setLinks(normalizeLinks(request.links()));
                }
                if (request.tags() != null) {
                    spec.setTags(normalizeTags(request.tags()));
                }
                return client.update(project)
                    .flatMap(updated -> clearRemovedModuleRefs(owner, name, previousModules, nextModules)
                        .thenReturn(updated));
            })
            .flatMap(project -> appendChange(owner, "project", project.getMetadata().getName(),
                false, Instant.now().toString(), serialize(ProjectView.of(project)))
                .thenReturn(project))
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    private Mono<Void> clearRemovedModuleRefs(String owner, String project,
        LinkedHashSet<String> previousModules, List<String> nextModules) {
        var removed = new LinkedHashSet<>(previousModules);
        removed.removeAll(nextModules);
        if (removed.isEmpty()) {
            return Mono.empty();
        }
        var todos = listOwned(WorkspaceTodo.class, owner, Sort.unsorted())
            .filter(todo -> todo.getSpec() != null
                && project.equals(todo.getSpec().getProject())
                && removed.contains(todo.getSpec().getModule()))
            .flatMap(todo -> {
                todo.getSpec().setModule(null);
                return client.update(todo);
            })
            .then();
        var notes = listOwned(WorkspaceNote.class, owner, Sort.unsorted())
            .filter(note -> note.getSpec() != null
                && project.equals(note.getSpec().getProject())
                && removed.contains(note.getSpec().getModule()))
            .flatMap(note -> {
                note.getSpec().setModule(null);
                return client.update(note);
            })
            .then();
        return todos.then(notes);
    }

    private static void validateTodoModule(WorkspaceTodo.Spec spec, WorkspaceProject project) {
        if (spec.getModule() == null) {
            return;
        }
        if (project == null || project.getSpec() == null
            || !nullSafe(project.getSpec().getModules()).contains(spec.getModule())) {
            spec.setModule(null);
        }
    }

    public Mono<WorkspaceProject> renameModule(String owner, String name,
        ModuleRenameRequest request) {
        var from = request.from().trim();
        var to = request.to().trim();
        if (to.isEmpty()) {
            return Mono.error(ApiException.badRequest("模块名称不能为空"));
        }
        if (to.length() > 32) {
            return Mono.error(ApiException.badRequest("单个模块不能超过 32 个字符"));
        }
        return getProject(owner, name)
            .flatMap(project -> {
                var modules = new ArrayList<>(nullSafe(project.getSpec().getModules()));
                var index = modules.indexOf(from);
                if (index < 0) {
                    return Mono.error(ApiException.badRequest("模块不存在"));
                }
                if (from.equals(to)) {
                    return Mono.just(project);
                }
                if (modules.contains(to)) {
                    return Mono.error(ApiException.badRequest("已存在同名模块"));
                }
                modules.set(index, to);
                project.getSpec().setModules(normalizeModules(modules));
                return client.update(project)
                    .flatMap(updated -> migrateTodoModule(owner, name, from, to)
                        .then(migrateNoteModule(owner, name, from, to))
                        .thenReturn(updated));
            })
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    private Mono<Void> migrateTodoModule(String owner, String project, String from, String to) {
        return listOwned(WorkspaceTodo.class, owner, Sort.unsorted())
            .filter(todo -> todo.getSpec() != null
                && project.equals(todo.getSpec().getProject())
                && from.equals(todo.getSpec().getModule()))
            .flatMap(todo -> {
                todo.getSpec().setModule(to);
                return client.update(todo);
            })
            .then();
    }

    private Mono<Void> migrateNoteModule(String owner, String project, String from, String to) {
        return listOwned(WorkspaceNote.class, owner, Sort.unsorted())
            .filter(note -> note.getSpec() != null
                && project.equals(note.getSpec().getProject())
                && from.equals(note.getSpec().getModule()))
            .flatMap(note -> {
                note.getSpec().setModule(to);
                return client.update(note);
            })
            .then();
    }

    public Mono<Void> deleteProject(String owner, String name) {
        return getProject(owner, name)
            .flatMap(project -> countTodosInProject(owner, name)
                .flatMap(count -> {
                    if (count > 0) {
                        return Mono.error(ApiException.conflict(
                            "该项目下还有 " + count + " 个待办，请先移动或删除待办"));
                    }
                    return client.delete(project).thenReturn(project);
                }))
            .flatMap(project -> appendChange(owner, "project", project.getMetadata().getName(),
                true, Instant.now().toString(), null))
            .then();
    }

    public Flux<WorkspaceQuickCmd> listCommands(String owner) {
        return listOwned(WorkspaceQuickCmd.class, owner, CREATED_DESC);
    }

    public Mono<WorkspaceQuickCmd> getCommand(String owner, String name) {
        return client.fetch(WorkspaceQuickCmd.class, name)
            .filter(command -> command.getSpec() != null
                && owner.equals(command.getSpec().getOwner()))
            .switchIfEmpty(Mono.error(ApiException.notFound()));
    }

    public Mono<WorkspaceQuickCmd> createCommand(String owner, QuickCmdUpsertRequest request) {
        var command = new WorkspaceQuickCmd();
        command.setMetadata(newMetadata());
        var spec = new WorkspaceQuickCmd.Spec();
        spec.setOwner(owner);
        applyCommand(spec, request);
        command.setSpec(spec);
        return client.create(command);
    }

    public Mono<WorkspaceQuickCmd> updateCommand(String owner, String name,
        QuickCmdUpsertRequest request) {
        return getCommand(owner, name)
            .flatMap(command -> {
                applyCommand(command.getSpec(), request);
                return client.update(command);
            })
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    public Mono<Void> deleteCommand(String owner, String name) {
        return getCommand(owner, name)
            .flatMap(command -> client.delete(command))
            .then();
    }

    public Flux<WorkspaceNote> listNotes(String owner) {
        return listOwned(WorkspaceNote.class, owner, CREATED_DESC);
    }

    public Mono<WorkspaceNote> getNote(String owner, String name) {
        return client.fetch(WorkspaceNote.class, name)
            .filter(note -> note.getSpec() != null && owner.equals(note.getSpec().getOwner()))
            .switchIfEmpty(Mono.error(ApiException.notFound()));
    }

    public Mono<WorkspaceNote> createNote(String owner, NoteUpsertRequest request) {
        var note = new WorkspaceNote();
        note.setMetadata(newMetadata());
        var spec = new WorkspaceNote.Spec();
        spec.setOwner(owner);
        applyNote(spec, request);
        spec.setUpdatedAt(Instant.now().toString());
        note.setSpec(spec);
        return client.create(note)
            .flatMap(created -> appendChange(owner, "note", created.getMetadata().getName(),
                false, Instant.now().toString(), serialize(NoteView.of(created)))
                .thenReturn(created));
    }

    public Mono<WorkspaceNote> updateNote(String owner, String name, NoteUpsertRequest request) {
        return getNote(owner, name)
            .flatMap(note -> {
                applyNote(note.getSpec(), request);
                note.getSpec().setUpdatedAt(Instant.now().toString());
                return client.update(note);
            })
            .flatMap(updated -> appendChange(owner, "note", updated.getMetadata().getName(),
                false, Instant.now().toString(), serialize(NoteView.of(updated)))
                .thenReturn(updated))
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    public Mono<WorkspaceNote> linkNotePost(String owner, String name, String post) {
        return getNote(owner, name)
            .flatMap(note -> {
                note.getSpec().setPost(post);
                note.getSpec().setUpdatedAt(Instant.now().toString());
                return client.update(note);
            })
            .flatMap(updated -> appendChange(owner, "note", updated.getMetadata().getName(),
                false, Instant.now().toString(), serialize(NoteView.of(updated)))
                .thenReturn(updated))
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    // 文章导入按 post 去重：已关联的笔记只更新标题与正文，保留项目、待办等本地属性
    public Mono<WorkspaceNote> importNoteFromPost(String owner, String post,
        NoteUpsertRequest request) {
        return listNotes(owner)
            .filter(note -> note.getSpec() != null && post.equals(note.getSpec().getPost()))
            .next()
            .flatMap(note -> {
                note.getSpec().setTitle(request.title().trim());
                note.getSpec().setContent(request.content() == null ? "" : request.content());
                note.getSpec().setSummary(null);
                note.getSpec().setUpdatedAt(Instant.now().toString());
                return client.update(note);
            })
            .switchIfEmpty(Mono.defer(() -> {
                var note = new WorkspaceNote();
                note.setMetadata(newMetadata());
                var spec = new WorkspaceNote.Spec();
                spec.setOwner(owner);
                applyNote(spec, request);
                spec.setPost(post);
                spec.setUpdatedAt(Instant.now().toString());
                note.setSpec(spec);
                return client.create(note);
            }))
            .flatMap(note -> appendChange(owner, "note", note.getMetadata().getName(),
                false, Instant.now().toString(), serialize(NoteView.of(note)))
                .thenReturn(note))
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    public Mono<Void> deleteNote(String owner, String name) {
        return getNote(owner, name)
            .flatMap(note -> client.delete(note).thenReturn(note))
            .flatMap(note -> appendChange(owner, "note", note.getMetadata().getName(),
                true, Instant.now().toString(), null))
            .then();
    }

    // 只更新摘要，不碰正文与更新时间，避免生成期间用户的编辑被旧内容覆盖
    public Mono<WorkspaceNote> updateNoteSummary(String owner, String name, String summary) {
        return getNote(owner, name)
            .flatMap(note -> {
                var text = summary == null ? "" : summary.trim();
                note.getSpec().setSummary(
                    text.isEmpty() ? null : text.substring(0, Math.min(text.length(), 1000)));
                return client.update(note);
            })
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    public Mono<List<SearchHit>> search(String owner, String keyword) {
        var needle = keyword.toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return Mono.just(List.of());
        }
        return Mono.zip(
                listOwned(WorkspaceTodo.class, owner, TODO_SORT).collectList(),
                listOwned(WorkspaceProject.class, owner, PROJECT_SORT).collectList(),
                listOwned(WorkspaceQuickCmd.class, owner, CREATED_DESC).collectList(),
                listOwned(WorkspaceNote.class, owner, CREATED_DESC).collectList())
            .map(tuple -> {
                var todoList = tuple.getT1();
                var projectList = tuple.getT2();
                var commandList = tuple.getT3();
                var noteList = tuple.getT4();
                var projectTitles = new HashMap<String, String>();
                var projectModules = new HashMap<String, List<String>>();
                for (var project : projectList) {
                    projectTitles.put(project.getMetadata().getName(),
                        project.getSpec().getTitle());
                    projectModules.put(project.getMetadata().getName(),
                        nullSafe(project.getSpec().getModules()));
                }
                var hits = new ArrayList<SearchHit>();
                todoList.stream()
                    .filter(todo -> !todo.getSpec().isDismissed())
                    .filter(todo -> matches(needle, todo.getSpec().getTitle(),
                        todo.getSpec().getDetail(), todo.getSpec().getReporter(),
                        todoModule(todo, projectModules),
                        projectTitles.get(todo.getSpec().getProject())))
                    .limit(SEARCH_LIMIT_PER_TYPE)
                    .map(todo -> new SearchHit("todo", todo.getMetadata().getName(),
                        todo.getSpec().getTitle(),
                        todoMeta(todo, projectTitles, projectModules)))
                    .forEach(hits::add);
                projectList.stream()
                    .filter(project -> matches(needle, project.getSpec().getTitle(),
                        project.getSpec().getNote(),
                        String.join(" ", nullSafe(project.getSpec().getModules())),
                        String.join(" ", nullSafe(project.getSpec().getLinks()))))
                    .limit(SEARCH_LIMIT_PER_TYPE)
                    .map(project -> new SearchHit("project", project.getMetadata().getName(),
                        project.getSpec().getTitle(),
                        nullSafe(project.getSpec().getModules()).size() + " 个模块 · "
                            + statusLabel(statusOf(project))))
                    .forEach(hits::add);
                commandList.stream()
                    .filter(command -> matches(needle, command.getSpec().getTitle(),
                        command.getSpec().getCommand(), command.getSpec().getNote(),
                        command.getSpec().getCategory()))
                    .limit(SEARCH_LIMIT_PER_TYPE)
                    .map(command -> new SearchHit("command", command.getMetadata().getName(),
                        command.getSpec().getTitle(),
                        nullIfBlank(command.getSpec().getCategory()) == null
                            ? "未分类" : command.getSpec().getCategory()))
                    .forEach(hits::add);
                noteList.stream()
                    .filter(note -> matches(needle, note.getSpec().getTitle(),
                        note.getSpec().getContent()))
                    .limit(SEARCH_LIMIT_PER_TYPE)
                    .map(note -> new SearchHit("note", note.getMetadata().getName(),
                        note.getSpec().getTitle(), noteMeta(note, projectTitles)))
                    .forEach(hits::add);
                return List.copyOf(hits);
            });
    }

    public Flux<WorkspaceTodo> streamTodos(String owner) {
        return listOwned(WorkspaceTodo.class, owner, TODO_SORT);
    }

    public Flux<WorkspaceProject> streamProjects(String owner) {
        return listOwned(WorkspaceProject.class, owner, PROJECT_SORT);
    }

    public Flux<WorkspaceQuickCmd> streamCommands(String owner) {
        return listOwned(WorkspaceQuickCmd.class, owner, CREATED_DESC);
    }

    public Flux<WorkspaceNote> streamNotes(String owner) {
        return listOwned(WorkspaceNote.class, owner, CREATED_DESC);
    }

    public BackupPayload.BackupTodo toBackupTodo(WorkspaceTodo todo) {
        return new BackupPayload.BackupTodo(
            todo.getMetadata().getName(),
            todo.getSpec().getTitle(),
            todo.getSpec().getDetail(),
            todo.getSpec().getPriority(),
            todo.getSpec().getProject(),
            todo.getSpec().getModule(),
            todo.getSpec().getProjectStatus(),
            todo.getSpec().getReporter(),
            todo.getSpec().getDueDate(),
            todo.getSpec().isCompleted(),
            todo.getSpec().getCompletedAt(),
            todo.getSpec().isDismissed(),
            todo.getSpec().getDismissedAt(),
            todo.getSpec().isPinned(),
            todo.getSpec().getRecurrence(),
            todo.getSpec().getSeriesId(),
            todo.getSpec().getCreatedAt() != null
                ? todo.getSpec().getCreatedAt()
                : todo.getMetadata().getCreationTimestamp() == null
                    ? null : todo.getMetadata().getCreationTimestamp().toString());
    }

    public BackupPayload.BackupProject toBackupProject(WorkspaceProject project) {
        var view = ProjectView.of(project);
        return new BackupPayload.BackupProject(
            project.getMetadata().getName(),
            project.getSpec().getTitle(),
            nullSafe(project.getSpec().getModules()),
            view.status(),
            null,
            project.getSpec().getNote(),
            nullSafe(project.getSpec().getLinks()),
            nullSafe(project.getSpec().getTags()),
            view.statusHistory().stream()
                .map(change -> new BackupPayload.BackupStatusChange(
                    change.status(), change.startedAt()))
                .toList());
    }

    public BackupPayload.BackupCommand toBackupCommand(WorkspaceQuickCmd command) {
        return new BackupPayload.BackupCommand(
            command.getSpec().getTitle(),
            command.getSpec().getCommand(),
            command.getSpec().getNote(),
            command.getSpec().getCategory(),
            nullSafe(command.getSpec().getTags()));
    }

    public BackupPayload.BackupNote toBackupNote(WorkspaceNote note) {
        return new BackupPayload.BackupNote(
            note.getSpec().getTitle(),
            note.getSpec().getContent(),
            note.getSpec().getSummary(),
            nullSafe(note.getSpec().getTags()),
            note.getSpec().getProject(),
            note.getSpec().getModule(),
            nullSafe(note.getSpec().getTodos()),
            note.getSpec().getUpdatedAt(),
            note.getSpec().getPost());
    }

    public Mono<BackupImportResult> importBackup(String owner, BackupPayload payload,
        boolean replace) {
        if (!"1".equals(payload.version())) {
            return Mono.error(ApiException.badRequest("备份文件版本不支持"));
        }
        var projects = new ArrayList<WorkspaceProject>();
        var projectNames = new HashMap<String, String>();
        var projectStatuses = new HashMap<String, String>();
        for (var source : nullSafe(payload.projects())) {
            var project = buildProject(owner, source.title(), source.modules(),
                source.status() != null ? source.status() : "DEV",
                source.note(), source.links(), source.tags());
            if (source.statusHistory() != null && !source.statusHistory().isEmpty()) {
                var history = source.statusHistory().stream()
                    .filter(change -> change != null && change.status() != null)
                    .map(change -> statusChange(change.status(),
                        change.startedAt() == null || change.startedAt().isBlank()
                            ? Instant.now().toString() : change.startedAt()))
                    .toList();
                if (!history.isEmpty()) {
                    project.getSpec().setStatusHistory(new ArrayList<>(history));
                    project.getSpec().setStatusHistory(ensureStatusHistory(project));
                }
            }
            projects.add(project);
            var oldName = trimToNull(source.name());
            if (oldName != null) {
                projectNames.putIfAbsent(oldName, project.getMetadata().getName());
                projectStatuses.put(project.getMetadata().getName(),
                    statusOf(project));
            }
        }
        var projectsByNewName = new HashMap<String, WorkspaceProject>();
        for (var project : projects) {
            projectsByNewName.put(project.getMetadata().getName(), project);
        }
        var todos = new ArrayList<WorkspaceTodo>();
        var todoNames = new HashMap<String, String>();
        for (var source : nullSafe(payload.todos())) {
            var projectName = trimToNull(source.project());
            var mappedProject = projectName == null ? null : projectNames.get(projectName);
            var todo = new WorkspaceTodo();
            todo.setMetadata(newMetadata());
            var spec = new WorkspaceTodo.Spec();
            spec.setOwner(owner);
            spec.setTitle(source.title().trim());
            spec.setDetail(source.detail() == null ? "" : source.detail());
            spec.setPriority(source.priority() == null ? "MEDIUM" : source.priority());
            spec.setCreatedAt(normalizeCreatedAt(source.createdAt()));
            spec.setProject(mappedProject);
            spec.setModule(moduleOfProject(
                mappedProject == null ? null : projectsByNewName.get(mappedProject), source.module()));
            spec.setProjectStatus(mappedProject == null ? null
                : source.projectStatus() != null ? source.projectStatus()
                    : projectStatuses.getOrDefault(mappedProject, "DEV"));
            spec.setReporter(trimToNull(source.reporter()));
            spec.setDueDate(normalizeDueDate(source.dueDate()));
            var dismissed = Boolean.TRUE.equals(source.dismissed());
            var completed = !dismissed && Boolean.TRUE.equals(source.completed());
            spec.setCompleted(completed);
            spec.setCompletedAt(completed ? parseInstantOrNow(source.completedAt()) : null);
            spec.setDismissed(dismissed);
            spec.setDismissedAt(dismissed && trimToNull(source.dismissedAt()) != null
                ? parseInstantOrNow(source.dismissedAt())
                : null);
            spec.setPinned(Boolean.TRUE.equals(source.pinned()));
            var recurrence = spec.getDueDate() == null
                ? null : normalizeRecurrence(source.recurrence());
            spec.setRecurrence(recurrence == null ? "NONE" : recurrence);
            spec.setSeriesId("NONE".equals(spec.getRecurrence())
                ? null : trimToNull(source.seriesId()));
            if (isRecurring(spec) && spec.getSeriesId() == null) {
                spec.setSeriesId(UUID.randomUUID().toString());
            }
            todo.setSpec(spec);
            var oldTodoName = trimToNull(source.name());
            if (oldTodoName != null) {
                todoNames.putIfAbsent(oldTodoName, todo.getMetadata().getName());
            }
            todos.add(todo);
        }
        var commands = new ArrayList<WorkspaceQuickCmd>();
        for (var source : nullSafe(payload.commands())) {
            var command = new WorkspaceQuickCmd();
            command.setMetadata(newMetadata());
            var spec = new WorkspaceQuickCmd.Spec();
            spec.setOwner(owner);
            spec.setTitle(source.title().trim());
            spec.setCommand(source.command());
            spec.setNote(trimToNull(source.note()));
            spec.setCategory(trimToNull(source.category()));
            spec.setTags(normalizeTags(source.tags()));
            command.setSpec(spec);
            commands.add(command);
        }
        var notes = new ArrayList<WorkspaceNote>();
        for (var source : nullSafe(payload.notes())) {
            var note = new WorkspaceNote();
            note.setMetadata(newMetadata());
            var spec = new WorkspaceNote.Spec();
            spec.setOwner(owner);
            spec.setTitle(source.title().trim());
            spec.setContent(source.content() == null ? "" : source.content());
            spec.setSummary(trimToNull(source.summary()));
            spec.setTags(normalizeTags(source.tags()));
            var noteProjectName = trimToNull(source.project());
            var mappedNoteProject = noteProjectName == null
                ? null : projectNames.get(noteProjectName);
            spec.setProject(mappedNoteProject);
            spec.setModule(moduleOfProject(
                mappedNoteProject == null ? null : projectsByNewName.get(mappedNoteProject),
                source.module()));
            spec.setTodos(normalizeNames(remapTodoNames(source.todos(), todoNames), 50));
            spec.setUpdatedAt(parseInstantOrNow(source.updatedAt()));
            spec.setPost(normalizePost(source.post()));
            note.setSpec(spec);
            notes.add(note);
        }
        Mono<Void> deletion = replace
            ? deleteAllOwned(WorkspaceTodo.class, owner)
                .then(deleteAllOwned(WorkspaceProject.class, owner))
                .then(deleteAllOwned(WorkspaceQuickCmd.class, owner))
                .then(deleteAllOwned(WorkspaceNote.class, owner))
            : Mono.empty();
        return deletion
            .thenMany(Flux.fromIterable(projects).concatMap(client::create))
            .thenMany(Flux.fromIterable(todos).concatMap(client::create))
            .thenMany(Flux.fromIterable(commands).concatMap(client::create))
            .thenMany(Flux.fromIterable(notes).concatMap(client::create))
            .then(Mono.fromSupplier(() -> new BackupImportResult(todos.size(), projects.size(),
                commands.size(), notes.size())));
    }

    private static String serialize(Object view) {
        return SYNC_JSON.writeValueAsString(view);
    }

    /**
     * Appends one change-log entry. A best-effort retention pass trims the oldest entries before
     * writing when this owner already holds at least {@link #SYNC_LOG_KEEP} entries.
     */
    private Mono<WorkspaceSyncLog> appendChange(String owner, String kind, String name,
        boolean deleted, String updatedAt, String payloadJson) {
        return trimSyncLogs(owner).then(Mono.defer(() -> {
            var log = new WorkspaceSyncLog();
            log.setMetadata(newMetadata());
            var spec = new WorkspaceSyncLog.Spec();
            spec.setOwner(owner);
            spec.setSeq(nextSyncSeq());
            spec.setKind(kind);
            spec.setName(name);
            spec.setDeleted(deleted);
            spec.setUpdatedAt(updatedAt);
            spec.setPayloadJson(payloadJson);
            log.setSpec(spec);
            return client.create(log);
        }));
    }

    /** Best-effort: when at or above the cap, delete the oldest batch before a new write. */
    private Mono<Void> trimSyncLogs(String owner) {
        var options = ListOptions.builder()
            .fieldQuery(Queries.equal("spec.owner", owner))
            .build();
        return client.listAll(WorkspaceSyncLog.class, options, Sort.by("spec.seq"))
            .count()
            .flatMap(count -> {
                if (count < SYNC_LOG_KEEP) {
                    return Mono.empty();
                }
                return client.listAll(WorkspaceSyncLog.class, options, Sort.by("spec.seq"))
                    .take(SYNC_LOG_TRIM_BATCH)
                    .flatMap(client::delete)
                    .then();
            });
    }

    /**
     * Incremental-sync query. cursor/oldestSeq are computed over ALL logs of the owner;
     * the limit applies only to the returned change entries.
     */
    public Mono<ChangesResponse> changes(String owner, long since, int limit) {
        var options = ListOptions.builder()
            .fieldQuery(Queries.equal("spec.owner", owner))
            .build();
        return client.listAll(WorkspaceSyncLog.class, options, Sort.by("spec.seq"))
            .collectList()
            .map(all -> {
                var cursor = all.stream()
                    .mapToLong(log -> log.getSpec().getSeq())
                    .max();
                var oldestSeq = all.stream()
                    .mapToLong(log -> log.getSpec().getSeq())
                    .min();
                var matching = all.stream()
                    .filter(log -> log.getSpec().getSeq() >= since)
                    .sorted(Comparator
                        .comparingLong((WorkspaceSyncLog log) -> log.getSpec().getSeq())
                        .thenComparing(log -> log.getSpec().getKind() == null
                            ? "" : log.getSpec().getKind())
                        .thenComparing(log -> log.getSpec().getName() == null
                            ? "" : log.getSpec().getName()))
                    .toList();
                var hasMore = matching.size() > limit;
                var entries = matching.stream()
                    .limit(limit)
                    .map(this::toChangeEntry)
                    .toList();
                return new ChangesResponse(
                    cursor.isPresent() ? cursor.getAsLong() : null,
                    oldestSeq.isPresent() ? oldestSeq.getAsLong() : null,
                    hasMore,
                    entries
                );
            });
    }

    private ChangeEntry toChangeEntry(WorkspaceSyncLog log) {
        var spec = log.getSpec();
        JsonNode payload = null;
        if (!spec.isDeleted() && spec.getPayloadJson() != null) {
            payload = SYNC_JSON.readTree(spec.getPayloadJson());
        }
        return new ChangeEntry(
            spec.getSeq(),
            spec.getKind(),
            spec.getName(),
            spec.isDeleted(),
            spec.getUpdatedAt(),
            payload
        );
    }

    private <E extends AbstractExtension> Flux<E> listOwned(Class<E> type, String owner,
        Sort sort) {
        var options = ListOptions.builder().fieldQuery(Queries.equal("spec.owner", owner)).build();
        return client.listAll(type, options, sort);
    }

    private <E extends AbstractExtension> Mono<Void> deleteAllOwned(Class<E> type,
        String owner) {
        return listOwned(type, owner, Sort.unsorted())
            .flatMap(client::delete)
            .then();
    }

    private Mono<WorkspaceProject> lookupProject(String owner, String project) {
        if (project == null || project.isBlank()) {
            return Mono.empty();
        }
        return getProject(owner, project.trim());
    }

    private Mono<Long> countTodosInProject(String owner, String project) {
        var options = ListOptions.builder().fieldQuery(Queries.equal("spec.owner", owner)).build();
        return client.listAll(WorkspaceTodo.class, options, Sort.unsorted())
            .filter(todo -> todo.getSpec() != null
                && project.equals(todo.getSpec().getProject()))
            .count();
    }

    private Mono<ListResult<WorkspaceTodo>> listTodoBy(Condition condition,
        PageRequestImpl pageRequest) {
        var options = ListOptions.builder().fieldQuery(condition).build();
        return client.listBy(WorkspaceTodo.class, options, pageRequest);
    }

    private WorkspaceProject buildProject(String owner, String title, List<String> modules,
        String status, String note, List<String> links, List<String> tags) {
        var project = new WorkspaceProject();
        project.setMetadata(newMetadata());
        var spec = new WorkspaceProject.Spec();
        spec.setOwner(owner);
        spec.setTitle(title.trim());
        spec.setModules(normalizeModules(modules));
        var normalizedStatus = normalizeStatus(status);
        spec.setStatus(normalizedStatus);
        spec.setStatusHistory(new ArrayList<>(List.of(
            statusChange(normalizedStatus, Instant.now().toString()))));
        spec.setNote(trimToNull(note));
        spec.setLinks(normalizeLinks(links));
        spec.setTags(normalizeTags(tags));
        project.setSpec(spec);
        return project;
    }

    private static List<WorkspaceProject.StatusChange> normalizeStatusHistory(
        List<ProjectUpsertRequest.StatusChangeRequest> changes) {
        if (changes == null || changes.isEmpty()) {
            throw ApiException.badRequest("状态时间线不能为空");
        }
        var history = new ArrayList<WorkspaceProject.StatusChange>();
        LocalDate previousDate = null;
        String previousStatus = null;
        for (var change : changes) {
            if (change == null) {
                throw ApiException.badRequest("状态时间线包含空记录");
            }
            if (change.status().equals(previousStatus)) {
                throw ApiException.badRequest("相邻阶段状态不能相同");
            }
            var startedAt = change.startedAt().trim();
            var date = parseStatusDate(startedAt);
            if (previousDate != null && date.isBefore(previousDate)) {
                throw ApiException.badRequest("状态时间线日期需按顺序排列");
            }
            history.add(statusChange(change.status(), startedAt));
            previousDate = date;
            previousStatus = change.status();
        }
        return history;
    }

    private static LocalDate parseStatusDate(String value) {
        try {
            return OffsetDateTime.parse(value)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDate();
        } catch (DateTimeParseException error) {
            throw ApiException.badRequest("状态时间线日期格式不正确");
        }
    }

    private static void applyStatus(WorkspaceProject project, String status) {
        var spec = project.getSpec();
        var current = statusOf(project);
        var next = normalizeStatus(status);
        var history = ensureStatusHistory(project);
        if (!current.equals(next)) {
            history.add(statusChange(next, Instant.now().toString()));
            spec.setStatus(next);
        }
        spec.setStatusHistory(history);
    }

    private static List<WorkspaceProject.StatusChange> ensureStatusHistory(
        WorkspaceProject project) {
        var spec = project.getSpec();
        var history = new ArrayList<>(nullSafe(spec.getStatusHistory()));
        history.removeIf(change -> change == null || change.getStatus() == null);
        var current = statusOf(project);
        if (history.isEmpty()) {
            var metadata = project.getMetadata();
            var startedAt = metadata == null || metadata.getCreationTimestamp() == null
                ? Instant.now().toString()
                : metadata.getCreationTimestamp().toString();
            history.add(statusChange(current, startedAt));
        } else if (!current.equals(history.get(history.size() - 1).getStatus())) {
            history.add(statusChange(current, Instant.now().toString()));
        }
        return history;
    }

    private static WorkspaceProject.StatusChange statusChange(String status, String startedAt) {
        var change = new WorkspaceProject.StatusChange();
        change.setStatus(status);
        change.setStartedAt(startedAt);
        return change;
    }

    private static void applyCommand(WorkspaceQuickCmd.Spec spec,
        QuickCmdUpsertRequest request) {
        spec.setTitle(request.title().trim());
        spec.setCommand(request.command());
        spec.setNote(trimToNull(request.note()));
        spec.setCategory(trimToNull(request.category()));
        spec.setTags(normalizeTags(request.tags()));
    }

    private static void applyNote(WorkspaceNote.Spec spec, NoteUpsertRequest request) {
        spec.setTitle(request.title().trim());
        spec.setContent(request.content() == null ? "" : request.content());
        if (request.summary() != null) {
            spec.setSummary(trimToNull(request.summary()));
        }
        spec.setTags(normalizeTags(request.tags()));
        spec.setProject(normalizeProject(request.project()));
        spec.setModule(normalizeModule(request.project(), request.module()));
        spec.setTodos(normalizeNames(request.todos(), 50));
    }

    private static void apply(WorkspaceTodo.Spec spec, String owner, TodoUpsertRequest request,
        boolean creating) {
        spec.setOwner(owner);
        spec.setTitle(request.title().trim());
        spec.setDetail(request.detail() == null ? "" : request.detail());
        spec.setPriority(request.priority());
        spec.setProject(normalizeProject(request.project()));
        spec.setModule(normalizeModule(request.project(), request.module()));
        if (creating || request.reporter() != null) {
            spec.setReporter(trimToNull(request.reporter()));
        }
        spec.setDueDate(normalizeDueDate(request.dueDate()));
        var recurrence = normalizeRecurrence(request.recurrence());
        if (recurrence != null) {
            spec.setRecurrence(recurrence);
        } else if (creating) {
            spec.setRecurrence("NONE");
        }
        if (isRecurring(spec)) {
            if (spec.getDueDate() == null) {
                throw ApiException.badRequest("重复任务需要设置截止日期");
            }
            if (spec.getSeriesId() == null) {
                spec.setSeriesId(UUID.randomUUID().toString());
            }
        } else if (creating) {
            spec.setSeriesId(null);
        }
        if (creating || request.createdAt() != null) {
            spec.setCreatedAt(normalizeCreatedAt(request.createdAt()));
        }
        var requestDismissed = Boolean.TRUE.equals(request.dismissed());
        if (requestDismissed && Boolean.TRUE.equals(request.completed())) {
            throw ApiException.badRequest("completed 与 dismissed 不能同时为 true");
        }
        if (creating || request.dismissed() != null) {
            var wasDismissed = spec.isDismissed();
            spec.setDismissed(requestDismissed);
            if (requestDismissed) {
                spec.setCompleted(false);
                spec.setCompletedAt(null);
                if (!wasDismissed || spec.getDismissedAt() == null) {
                    spec.setDismissedAt(Instant.now().toString());
                }
            } else {
                spec.setDismissedAt(null);
            }
        }
        Boolean target = creating
            ? Boolean.valueOf(Boolean.TRUE.equals(request.completed()))
            : request.completed();
        if (creating || target != null) {
            var wasCompleted = spec.isCompleted();
            var completed = Boolean.TRUE.equals(target);
            if (creating || completed != wasCompleted) {
                spec.setCompleted(completed);
                spec.setCompletedAt(completed ? Instant.now().toString() : null);
            }
            if (completed) {
                spec.setDismissed(false);
                spec.setDismissedAt(null);
            }
        }
        if (creating || request.pinned() != null) {
            spec.setPinned(Boolean.TRUE.equals(request.pinned()));
        }
    }

    private static Metadata newMetadata() {
        var metadata = new Metadata();
        metadata.setName(UUID.randomUUID().toString());
        return metadata;
    }

    private static String normalizeRecurrence(String recurrence) {
        if (recurrence == null || recurrence.isBlank()) {
            return null;
        }
        var value = recurrence.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "NONE", "DAILY", "WEEKLY", "MONTHLY" -> value;
            default -> null;
        };
    }

    private static String normalizeProject(String project) {
        if (project == null || project.isBlank()) {
            return null;
        }
        return project.trim();
    }

    private static String normalizeModule(String project, String module) {
        if (project == null || project.isBlank() || module == null || module.isBlank()) {
            return null;
        }
        return module.trim();
    }

    private static String moduleOfProject(WorkspaceProject project, String module) {
        if (project == null || project.getSpec() == null || module == null || module.isBlank()) {
            return null;
        }
        var trimmed = module.trim();
        return nullSafe(project.getSpec().getModules()).contains(trimmed) ? trimmed : null;
    }

    private static List<String> normalizeModules(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (var module : modules) {
            if (module == null) {
                continue;
            }
            var trimmed = module.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeLinks(List<String> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (var link : links) {
            var trimmed = trimToNull(link);
            if (trimmed != null) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (var tag : tags) {
            if (tag == null) {
                continue;
            }
            var trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
            if (normalized.size() >= 10) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeNames(List<String> names, int max) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (var name : names) {
            if (name == null) {
                continue;
            }
            var trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
            if (normalized.size() >= max) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> remapTodoNames(List<String> names, Map<String, String> mapping) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        var mapped = new ArrayList<String>();
        for (var name : names) {
            var trimmed = trimToNull(name);
            if (trimmed == null) {
                continue;
            }
            var target = mapping.get(trimmed);
            if (target != null) {
                mapped.add(target);
            }
        }
        return mapped;
    }

    private static String normalizeDueDate(String dueDate) {
        if (dueDate == null || dueDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dueDate.trim()).toString();
        } catch (DateTimeParseException error) {
            throw ApiException.badRequest("截止日期格式应为 YYYY-MM-DD");
        }
    }

    private static String normalizePost(String post) {
        var text = trimToNull(post);
        return text != null && POST_NAME.matcher(text).matches() ? text : null;
    }

    private static String normalizeCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) {
            return null;
        }
        var text = createdAt.trim();
        try {
            return OffsetDateTime.parse(text).toInstant().toString();
        } catch (DateTimeParseException ignored) {
            // Try a local date-time without an offset next.
        }
        try {
            return LocalDateTime.parse(text)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toString();
        } catch (DateTimeParseException error) {
            throw ApiException.badRequest("创建时间格式应为 ISO-8601，例如 2026-11-10T16:46:00+08:00");
        }
    }

    private static String statusOf(WorkspaceProject project) {
        return ProjectView.statusOf(project);
    }

    private static String normalizeStatus(String status) {
        return status == null || status.isBlank() ? "NOT_STARTED" : status;
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "NOT_STARTED" -> "未开始";
            case "TEST" -> "测试中";
            case "RELEASED" -> "已上线";
            case "ACCEPTED" -> "已验收";
            default -> "开发中";
        };
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nullIfBlank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String parseInstantOrNow(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now().toString();
        }
        try {
            return Instant.parse(value.trim()).toString();
        } catch (DateTimeParseException error) {
            return Instant.now().toString();
        }
    }

    private static boolean matches(String needle, String... values) {
        for (var value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String todoModule(WorkspaceTodo todo,
        Map<String, List<String>> projectModules) {
        var module = todo.getSpec().getModule();
        if (module == null || module.isBlank()) {
            return null;
        }
        var modules = projectModules.get(todo.getSpec().getProject());
        return modules != null && modules.contains(module) ? module : null;
    }

    private static String todoMeta(WorkspaceTodo todo, Map<String, String> projectTitles,
        Map<String, List<String>> projectModules) {
        var project = todo.getSpec().getProject() == null
            ? null : projectTitles.get(todo.getSpec().getProject());
        var parts = new ArrayList<String>();
        parts.add(project == null || project.isBlank() ? "未分类" : project);
        var module = todoModule(todo, projectModules);
        if (module != null) {
            parts.add(module);
        }
        return String.join(" / ", parts);
    }

    private static String noteMeta(WorkspaceNote note, Map<String, String> projectTitles) {
        var project = note.getSpec().getProject() == null
            ? null : projectTitles.get(note.getSpec().getProject());
        return project == null || project.isBlank() ? "笔记" : project;
    }

    private static <T> List<T> nullSafe(List<T> values) {
        return values == null ? List.of() : values;
    }
}
