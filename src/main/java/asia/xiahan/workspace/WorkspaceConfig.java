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
import run.halo.app.plugin.PluginContext;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.app.theme.router.ModelConst;

@Configuration(proxyBeanMethods = false)
public class WorkspaceConfig {

    @Bean
    public LocalValidatorFactoryBean validator() {
        return new LocalValidatorFactoryBean();
    }

    @Bean
    public RouterFunction<ServerResponse> workspacePageRouter(TemplateNameResolver templateNameResolver,
        CurrentUser currentUser, ObjectProvider<PluginContext> pluginContextProvider) {
        return RouterFunctions.route()
            .GET("/workspace", request -> renderWorkspace(request, templateNameResolver,
                currentUser, pluginContextProvider))
            .build();
    }

    private Mono<ServerResponse> renderWorkspace(ServerRequest request,
        TemplateNameResolver templateNameResolver, CurrentUser currentUser,
        ObjectProvider<PluginContext> pluginContextProvider) {
        return currentUser.findUsername()
            .flatMap(username -> {
                var model = new HashMap<String, Object>();
                model.put(ModelConst.TEMPLATE_ID, "plugin:personal-workspace:workspace");
                model.put("username", username);
                var pluginContext = pluginContextProvider.getIfAvailable();
                model.put("pluginVersion", pluginContext == null ? "" : pluginContext.getVersion());
                return templateNameResolver.resolveTemplateNameOrDefault(request.exchange(), "workspace")
                    .flatMap(templateName -> ServerResponse.ok()
                        .headers(headers -> headers.setCacheControl("no-store"))
                        .render(templateName, model));
            })
            .switchIfEmpty(Mono.defer(() -> ServerResponse.status(HttpStatus.FOUND)
                .location(URI.create("/login?redirect_uri="
                    + URLEncoder.encode("/workspace", StandardCharsets.UTF_8)))
                .build()));
    }
}
