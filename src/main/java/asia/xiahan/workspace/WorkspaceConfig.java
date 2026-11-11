package asia.xiahan.workspace;

import asia.xiahan.workspace.dto.AiImportRequest;
import asia.xiahan.workspace.dto.AiImportResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.app.theme.router.ModelConst;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class WorkspaceConfig {

    private static final String SETTINGS_CONFIG_MAP = "personal-workspace-config";
    private static final String DEFAULT_COLLAPSE_SHORTCUT = "z+d+v";
    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    @Bean
    public RouterFunction<ServerResponse> workspaceAiRouter(CurrentUser currentUser,
        AiImportService aiImportService) {
        return RouterFunctions.route()
            .POST("/workspace/api/ai-import", request -> aiImport(request, currentUser, aiImportService))
            .build();
    }

    // 页面会话鉴权的接口不放 /apis 下，避免走 k8s 式 RBAC；CSRF 由 Halo 自带的过滤链校验
    private static Mono<ServerResponse> aiImport(ServerRequest request, CurrentUser currentUser,
        AiImportService aiImportService) {
        return currentUser.findUsername()
            .switchIfEmpty(Mono.error(ApiException.unauthorized()))
            .flatMap(username -> request.bodyToMono(AiImportRequest.class)
                .switchIfEmpty(Mono.error(ApiException.badRequest("请求体为空")))
                .flatMap(body -> {
                    var text = body.text() == null ? "" : body.text();
                    if (text.isBlank()) {
                        return Mono.error(ApiException.badRequest("请先粘贴内容"));
                    }
                    if (text.length() > 20000) {
                        return Mono.error(ApiException.badRequest("粘贴内容不能超过 20000 个字符"));
                    }
                    return aiImportService.analyze(username, text);
                })
                .map(AiImportResponse::new)
                .flatMap(result -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cache-Control", "no-store")
                    .bodyValue(result)))
            .onErrorResume(ApiException.class, error -> errorResponse(error))
            .onErrorResume(error -> errorResponse(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "服务暂时不可用，请稍后重试")));
    }

    private static Mono<ServerResponse> errorResponse(ApiException error) {
        return ServerResponse.status(error.getStatus())
            .contentType(MediaType.APPLICATION_JSON)
            .header("Cache-Control", "no-store")
            .bodyValue(Map.of("error", Map.of(
                "code", error.getCode(),
                "message", error.getMessage() == null ? "请求失败" : error.getMessage()
            )));
    }

    @Bean
    public LocalValidatorFactoryBean validator() {
        return new LocalValidatorFactoryBean();
    }

    @Bean
    public RouterFunction<ServerResponse> workspacePageRouter(TemplateNameResolver templateNameResolver,
        CurrentUser currentUser, ReactiveExtensionClient client,
        ObjectProvider<PluginContext> pluginContextProvider) {
        return RouterFunctions.route()
            .GET("/workspace", request -> renderWorkspace(request, templateNameResolver,
                currentUser, client, pluginContextProvider))
            .build();
    }

    private Mono<ServerResponse> renderWorkspace(ServerRequest request,
        TemplateNameResolver templateNameResolver, CurrentUser currentUser,
        ReactiveExtensionClient client, ObjectProvider<PluginContext> pluginContextProvider) {
        return currentUser.findUsername()
            .flatMap(username -> collapseShortcut(client).flatMap(shortcut -> {
                var model = new HashMap<String, Object>();
                model.put(ModelConst.TEMPLATE_ID, "plugin:personal-workspace:workspace");
                model.put("username", username);
                model.put("collapseShortcut", shortcut);
                var pluginContext = pluginContextProvider.getIfAvailable();
                model.put("pluginVersion", pluginContext == null ? "" : pluginContext.getVersion());
                return csrfToken(request).doOnNext(token -> model.put("csrfToken", token))
                    .flatMap(token -> templateNameResolver
                        .resolveTemplateNameOrDefault(request.exchange(), "workspace")
                        .flatMap(templateName -> ServerResponse.ok()
                            .headers(headers -> headers.setCacheControl("no-store"))
                            .render(templateName, model)));
            }))
            .switchIfEmpty(Mono.defer(() -> ServerResponse.status(HttpStatus.FOUND)
                .location(URI.create("/login?redirect_uri="
                    + URLEncoder.encode("/workspace", StandardCharsets.UTF_8)))
                .build()));
    }

    // 渲染时取 CSRF token（含 Halo 的 XOR 编码），前端提交 /workspace/api 下的 JSON 接口时回传
    private static Mono<String> csrfToken(ServerRequest request) {
        Mono<CsrfToken> token = request.exchange().getAttribute(CsrfToken.class.getName());
        if (token == null) return Mono.just("");
        return token.map(CsrfToken::getToken).defaultIfEmpty("")
            .onErrorResume(error -> Mono.just(""));
    }

    // 插件设置存放在 {plugin}-config 的 basic 组 JSON 里，读取失败时回落到默认快捷键
    private static Mono<String> collapseShortcut(ReactiveExtensionClient client) {
        return client.fetch(ConfigMap.class, SETTINGS_CONFIG_MAP)
            .mapNotNull(configMap -> configMap.getData() == null
                ? null : configMap.getData().get("basic"))
            .map(WorkspaceConfig::readCollapseShortcut)
            .filter(value -> !value.isBlank())
            .defaultIfEmpty(DEFAULT_COLLAPSE_SHORTCUT)
            .onErrorResume(error -> Mono.just(DEFAULT_COLLAPSE_SHORTCUT));
    }

    private static String readCollapseShortcut(String json) {
        try {
            var node = JSON_MAPPER.readTree(json);
            var field = node == null ? null : node.get("collapseShortcut");
            return field == null || field.isNull() ? "" : field.asText().trim();
        } catch (Exception error) {
            return "";
        }
    }
}
