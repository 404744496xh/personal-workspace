package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.BackupImportResult;
import asia.xiahan.workspace.dto.BackupPayload;
import asia.xiahan.workspace.dto.ModuleRenameRequest;
import asia.xiahan.workspace.dto.NoteUpsertRequest;
import asia.xiahan.workspace.dto.ProjectUpsertRequest;
import asia.xiahan.workspace.dto.QuickCmdUpsertRequest;
import asia.xiahan.workspace.dto.SearchHit;
import asia.xiahan.workspace.dto.TodoQuery;
import asia.xiahan.workspace.dto.TodoUpsertRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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

@Component
public class WorkspaceDataAccess {

    private static final Sort TODO_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final Sort PROJECT_SORT =
        Sort.by(Sort.Order.asc("metadata.creationTimestamp"));
    private static final Sort CREATED_DESC =
        Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final int SEARCH_LIMIT_PER_TYPE = 8;

    private final ReactiveExtensionClient client;

    public WorkspaceDataAccess(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<ListResult<WorkspaceTodo>> listTodos(String owner, TodoQuery query) {
        var condition = Queries.equal("spec.owner", owner);
        if (!query.keyword().isEmpty()) {
            condition = condition.and(Queries.or(
                Queries.contains("spec.title", query.keyword()),
                Queries.contains("spec.detail", query.keyword())
            ));
        }
        if ("OPEN".equals(query.status())) {
            condition = condition.and(Queries.equal("spec.completed", false));
        } else if ("DONE".equals(query.status())) {
            condition = condition.and(Queries.equal("spec.completed", true));
        }
        if (!query.priority().isEmpty()) {
            condition = condition.and(Queries.equal("spec.priority", query.priority()));
        }
        return listTodoBy(condition, PageRequestImpl.of(query.page(), query.size(), TODO_SORT));
    }

    public Mono<WorkspaceTodo> getTodo(String owner, String name) {
        return client.fetch(WorkspaceTodo.class, name)
            .filter(todo -> todo.getSpec() != null && owner.equals(todo.getSpec().getOwner()))
            .switchIfEmpty(Mono.error(ApiException.notFound()));
    }

    public Mono<WorkspaceTodo> createTodo(String owner, TodoUpsertRequest request) {
        return requireOwnedProject(owner, request.project())
            .then(Mono.defer(() -> {
                var todo = new WorkspaceTodo();
                todo.setMetadata(newMetadata());
                var spec = new WorkspaceTodo.Spec();
                apply(spec, owner, request, true);
                todo.setSpec(spec);
                return client.create(todo);
            }));
    }

    public Mono<WorkspaceTodo> updateTodo(String owner, String name, TodoUpsertRequest request) {
        return getTodo(owner, name)
            .flatMap(todo -> requireOwnedProject(owner, request.project())
                .then(Mono.fromSupplier(() -> {
                    apply(todo.getSpec(), owner, request, false);
                    return todo;
                })))
            .flatMap(client::update)
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    public Mono<Void> deleteTodo(String owner, String name) {
        return getTodo(owner, name)
            .flatMap(todo -> client.delete(todo))
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
                request.progress(), request.note(), request.links(), request.tags()))
            .flatMap(client::create);
    }

    public Mono<WorkspaceProject> updateProject(String owner, String name,
        ProjectUpsertRequest request) {
        return getProject(owner, name)
            .flatMap(project -> {
                var spec = project.getSpec();
                spec.setTitle(request.title().trim());
                spec.setModules(normalizeModules(request.modules()));
                if (request.progress() != null) {
                    spec.setProgress(clampProgress(request.progress()));
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
                return client.update(project);
            })
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
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

    public Mono<Void> deleteProject(String owner, String name) {
        return getProject(owner, name)
            .flatMap(project -> countTodosInProject(owner, name)
                .flatMap(count -> {
                    if (count > 0) {
                        return Mono.error(ApiException.conflict(
                            "该项目下还有 " + count + " 个待办，请先移动或删除待办"));
                    }
                    return client.delete(project);
                }))
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
        return client.create(note);
    }

    public Mono<WorkspaceNote> updateNote(String owner, String name, NoteUpsertRequest request) {
        return getNote(owner, name)
            .flatMap(note -> {
                applyNote(note.getSpec(), request);
                note.getSpec().setUpdatedAt(Instant.now().toString());
                return client.update(note);
            })
            .onErrorMap(OptimisticLockingFailureException.class, error -> ApiException.conflict());
    }

    public Mono<Void> deleteNote(String owner, String name) {
        return getNote(owner, name)
            .flatMap(note -> client.delete(note))
            .then();
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
                for (var project : projectList) {
                    projectTitles.put(project.getMetadata().getName(),
                        project.getSpec().getTitle());
                }
                var hits = new ArrayList<SearchHit>();
                todoList.stream()
                    .filter(todo -> matches(needle, todo.getSpec().getTitle(),
                        todo.getSpec().getDetail(), todo.getSpec().getModule(),
                        projectTitles.get(todo.getSpec().getProject())))
                    .limit(SEARCH_LIMIT_PER_TYPE)
                    .map(todo -> new SearchHit("todo", todo.getMetadata().getName(),
                        todo.getSpec().getTitle(), todoMeta(todo, projectTitles)))
                    .forEach(hits::add);
                projectList.stream()
                    .filter(project -> matches(needle, project.getSpec().getTitle(),
                        project.getSpec().getNote(),
                        String.join(" ", nullSafe(project.getSpec().getModules())),
                        String.join(" ", nullSafe(project.getSpec().getLinks()))))
                    .limit(SEARCH_LIMIT_PER_TYPE)
                    .map(project -> new SearchHit("project", project.getMetadata().getName(),
                        project.getSpec().getTitle(),
                        nullSafe(project.getSpec().getModules()).size() + " 个模块 · 进度 "
                            + (project.getSpec().getProgress() == null
                                ? 0 : project.getSpec().getProgress()) + "%"))
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

    public Mono<BackupPayload> exportBackup(String owner) {
        return Mono.zip(
                listOwned(WorkspaceTodo.class, owner, TODO_SORT).collectList(),
                listOwned(WorkspaceProject.class, owner, PROJECT_SORT).collectList(),
                listOwned(WorkspaceQuickCmd.class, owner, CREATED_DESC).collectList(),
                listOwned(WorkspaceNote.class, owner, CREATED_DESC).collectList())
            .map(tuple -> {
                var todos = tuple.getT1().stream()
                    .map(todo -> new BackupPayload.BackupTodo(
                        todo.getMetadata().getName(),
                        todo.getSpec().getTitle(),
                        todo.getSpec().getDetail(),
                        todo.getSpec().getPriority(),
                        todo.getSpec().getProject(),
                        todo.getSpec().getModule(),
                        todo.getSpec().getDueDate(),
                        todo.getSpec().isCompleted(),
                        todo.getSpec().getCompletedAt()))
                    .toList();
                var projects = tuple.getT2().stream()
                    .map(project -> new BackupPayload.BackupProject(
                        project.getMetadata().getName(),
                        project.getSpec().getTitle(),
                        nullSafe(project.getSpec().getModules()),
                        project.getSpec().getProgress(),
                        project.getSpec().getNote(),
                        nullSafe(project.getSpec().getLinks()),
                        nullSafe(project.getSpec().getTags())))
                    .toList();
                var commands = tuple.getT3().stream()
                    .map(command -> new BackupPayload.BackupCommand(
                        command.getSpec().getTitle(),
                        command.getSpec().getCommand(),
                        command.getSpec().getNote(),
                        command.getSpec().getCategory(),
                        nullSafe(command.getSpec().getTags())))
                    .toList();
                var notes = tuple.getT4().stream()
                    .map(note -> new BackupPayload.BackupNote(
                        note.getSpec().getTitle(),
                        note.getSpec().getContent(),
                        nullSafe(note.getSpec().getTags()),
                        note.getSpec().getProject(),
                        note.getSpec().getModule(),
                        nullSafe(note.getSpec().getTodos()),
                        note.getSpec().getUpdatedAt()))
                    .toList();
                return new BackupPayload("1", Instant.now().toString(), todos, projects,
                    commands, notes);
            });
    }

    public Mono<BackupImportResult> importBackup(String owner, BackupPayload payload,
        boolean replace) {
        if (!"1".equals(payload.version())) {
            return Mono.error(ApiException.badRequest("备份文件版本不支持"));
        }
        var projects = new ArrayList<WorkspaceProject>();
        var projectNames = new HashMap<String, String>();
        for (var source : nullSafe(payload.projects())) {
            var project = buildProject(owner, source.title(), source.modules(),
                source.progress(), source.note(), source.links(), source.tags());
            projects.add(project);
            var oldName = trimToNull(source.name());
            if (oldName != null) {
                projectNames.putIfAbsent(oldName, project.getMetadata().getName());
            }
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
            spec.setProject(mappedProject);
            spec.setModule(normalizeModule(mappedProject, source.module()));
            spec.setDueDate(normalizeDueDate(source.dueDate()));
            var completed = Boolean.TRUE.equals(source.completed());
            spec.setCompleted(completed);
            spec.setCompletedAt(completed ? parseInstantOrNow(source.completedAt()) : null);
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
            spec.setTags(normalizeTags(source.tags()));
            var noteProjectName = trimToNull(source.project());
            var mappedNoteProject = noteProjectName == null
                ? null : projectNames.get(noteProjectName);
            spec.setProject(mappedNoteProject);
            spec.setModule(normalizeModule(mappedNoteProject, source.module()));
            spec.setTodos(normalizeNames(remapTodoNames(source.todos(), todoNames), 50));
            spec.setUpdatedAt(parseInstantOrNow(source.updatedAt()));
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

    private Mono<Void> requireOwnedProject(String owner, String project) {
        if (project == null || project.isBlank()) {
            return Mono.empty();
        }
        return getProject(owner, project.trim()).then();
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
        Integer progress, String note, List<String> links, List<String> tags) {
        var project = new WorkspaceProject();
        project.setMetadata(newMetadata());
        var spec = new WorkspaceProject.Spec();
        spec.setOwner(owner);
        spec.setTitle(title.trim());
        spec.setModules(normalizeModules(modules));
        spec.setProgress(clampProgress(progress));
        spec.setNote(trimToNull(note));
        spec.setLinks(normalizeLinks(links));
        spec.setTags(normalizeTags(tags));
        project.setSpec(spec);
        return project;
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
        spec.setDueDate(normalizeDueDate(request.dueDate()));
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
        }
    }

    private static Metadata newMetadata() {
        var metadata = new Metadata();
        metadata.setName(UUID.randomUUID().toString());
        return metadata;
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

    private static int clampProgress(Integer progress) {
        if (progress == null) {
            return 0;
        }
        return Math.max(0, Math.min(100, progress));
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

    private static String todoMeta(WorkspaceTodo todo, Map<String, String> projectTitles) {
        var project = todo.getSpec().getProject() == null
            ? null : projectTitles.get(todo.getSpec().getProject());
        var parts = new ArrayList<String>();
        parts.add(project == null || project.isBlank() ? "未分类" : project);
        if (todo.getSpec().getModule() != null && !todo.getSpec().getModule().isBlank()) {
            parts.add(todo.getSpec().getModule());
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
