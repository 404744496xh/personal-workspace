package asia.xiahan.workspace;

import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.context.ServerSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.security.BeforeSecurityWebFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkspaceApiAuthFilter implements BeforeSecurityWebFilter {

    private static final String API_PREFIX = "/apis/api.workspace.halo.run/";
    private static final byte[] UNAUTHORIZED_BODY =
        "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"请先登录\"}}"
            .getBytes(StandardCharsets.UTF_8);

    private final ServerSecurityContextRepository securityContextRepository;

    public WorkspaceApiAuthFilter(ServerSecurityContextRepository securityContextRepository) {
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getPath().value().startsWith(API_PREFIX)) {
            return chain.filter(exchange);
        }
        // Let Halo's bearer-token authentication handle PAT requests.
        if (exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) {
            return chain.filter(exchange);
        }
        return securityContextRepository.load(exchange)
            .map(SecurityContext::getAuthentication)
            .filter(CurrentUser::isAuthenticatedUser)
            .hasElement()
            .flatMap(authenticated -> authenticated
                ? chain.filter(exchange)
                : unauthorized(exchange.getResponse()));
    }

    private static Mono<Void> unauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().setCacheControl("no-store");
        return response.writeWith(Mono.just(
            response.bufferFactory().wrap(UNAUTHORIZED_BODY)));
    }
}
