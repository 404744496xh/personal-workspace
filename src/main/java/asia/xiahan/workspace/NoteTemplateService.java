package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.NoteTemplate;
import asia.xiahan.workspace.dto.NoteTemplateRequest;
import asia.xiahan.workspace.dto.NoteTemplateResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

// 模板属于个人数据：ConfigMap 里按 owner 分桶保存，空列表表示用户主动删光了模板
@Component
public class NoteTemplateService {

    private static final String CONFIG_MAP = "personal-workspace-note-templates";
    private static final String DATA_KEY = "templates";
    private static final int MAX_TEMPLATES = 20;
    private static final int MAX_NAME = 50;
    private static final int MAX_CONTENT = 8000;
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private final ReactiveExtensionClient client;

    public NoteTemplateService(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<NoteTemplateResponse> get(String owner) {
        return client.fetch(ConfigMap.class, CONFIG_MAP)
            .mapNotNull(configMap -> configMap.getData() == null
                ? null : configMap.getData().get(DATA_KEY))
            .map(json -> readForOwner(json, owner))
            .map(NoteTemplateResponse::new)
            .defaultIfEmpty(new NoteTemplateResponse(null))
            .onErrorResume(error -> Mono.just(new NoteTemplateResponse(null)));
    }

    public Mono<NoteTemplateResponse> save(String owner, NoteTemplateRequest request) {
        var templates = normalize(request);
        return saveOnce(owner, templates)
            .retryWhen(Retry.max(1).filter(NoteTemplateService::retryable))
            .onErrorMap(OptimisticLockingFailureException.class,
                error -> ApiException.conflict("模板已被修改，请刷新后重试"))
            .map(NoteTemplateResponse::new);
    }

    private Mono<List<NoteTemplate>> saveOnce(String owner, List<NoteTemplate> templates) {
        return client.fetch(ConfigMap.class, CONFIG_MAP)
            .flatMap(configMap -> {
                var data = configMap.getData() == null
                    ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<>(configMap.getData());
                var all = readAll(data.get(DATA_KEY));
                all.put(owner, templates);
                data.put(DATA_KEY, writeAll(all));
                configMap.setData(data);
                return client.update(configMap);
            })
            .switchIfEmpty(Mono.defer(() -> create(owner, templates)))
            .thenReturn(templates);
    }

    private Mono<ConfigMap> create(String owner, List<NoteTemplate> templates) {
        var configMap = new ConfigMap();
        var metadata = new Metadata();
        metadata.setName(CONFIG_MAP);
        configMap.setMetadata(metadata);
        var data = new LinkedHashMap<String, String>();
        data.put(DATA_KEY, writeAll(Map.of(owner, templates)));
        configMap.setData(data);
        return client.create(configMap);
    }

    private static boolean retryable(Throwable error) {
        return error instanceof OptimisticLockingFailureException;
    }

    private static List<NoteTemplate> normalize(NoteTemplateRequest request) {
        if (request == null || request.templates() == null) {
            return List.of();
        }
        if (request.templates().size() > MAX_TEMPLATES) {
            throw ApiException.badRequest("笔记模板最多 " + MAX_TEMPLATES + " 个");
        }
        var templates = new ArrayList<NoteTemplate>();
        var names = new LinkedHashSet<String>();
        for (var template : request.templates()) {
            if (template == null) {
                throw ApiException.badRequest("模板内容不能为空");
            }
            var name = template.name() == null ? "" : template.name().trim();
            var content = template.content() == null ? "" : template.content().strip();
            if (name.isEmpty()) {
                throw ApiException.badRequest("模板名称不能为空");
            }
            if (name.length() > MAX_NAME) {
                throw ApiException.badRequest("模板名称不能超过 " + MAX_NAME + " 个字符");
            }
            if (content.isEmpty()) {
                throw ApiException.badRequest("模板内容不能为空");
            }
            if (content.length() > MAX_CONTENT) {
                throw ApiException.badRequest("单个模板内容不能超过 " + MAX_CONTENT + " 个字符");
            }
            if (!names.add(name)) {
                throw ApiException.badRequest("模板名称不能重复：" + name);
            }
            templates.add(new NoteTemplate(name, content));
        }
        return List.copyOf(templates);
    }

    private static List<NoteTemplate> readForOwner(String json, String owner) {
        var all = readAll(json);
        var templates = all.get(owner);
        if (templates == null) return null;
        return templates.stream()
            .filter(template -> template != null
                && template.name() != null && !template.name().isBlank()
                && template.content() != null && !template.content().isBlank())
            .map(template -> new NoteTemplate(template.name().trim(), template.content().strip()))
            .toList();
    }

    private static Map<String, List<NoteTemplate>> readAll(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            var all = JSON_MAPPER.readValue(json,
                new TypeReference<Map<String, List<NoteTemplate>>>() {});
            return all == null ? new LinkedHashMap<>() : new LinkedHashMap<>(all);
        } catch (Exception error) {
            return new LinkedHashMap<>();
        }
    }

    private static String writeAll(Map<String, List<NoteTemplate>> all) {
        try {
            return JSON_MAPPER.writeValueAsString(all);
        } catch (Exception error) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "模板保存失败，请稍后重试");
        }
    }
}
