package asia.xiahan.workspace;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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
                return templateNameResolver.resolveTemplateNameOrDefault(request.exchange(), "workspace")
                    .flatMap(templateName -> ServerResponse.ok()
                        .headers(headers -> headers.setCacheControl("no-store"))
                        .render(templateName, model));
            }))
            .switchIfEmpty(Mono.defer(() -> ServerResponse.status(HttpStatus.FOUND)
                .location(URI.create("/login?redirect_uri="
                    + URLEncoder.encode("/workspace", StandardCharsets.UTF_8)))
                .build()));
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
