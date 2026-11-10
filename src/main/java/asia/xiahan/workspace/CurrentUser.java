package asia.xiahan.workspace;

import java.util.Locale;
import java.util.Set;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class CurrentUser {

    private static final Set<String> ANONYMOUS_NAMES =
        Set.of("anonymous", "anonymoususer", "anonymous user");

    public Mono<String> findUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(context -> context.getAuthentication())
            .filter(CurrentUser::isAuthenticatedUser)
            .map(Authentication::getName)
            .map(String::trim);
    }

    public static boolean isAuthenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        if (authentication.getAuthorities().stream()
            .anyMatch(authority -> "ROLE_ANONYMOUS".equals(authority.getAuthority()))) {
            return false;
        }
        var name = authentication.getName();
        return name != null
            && !name.isBlank()
            && !ANONYMOUS_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    public Mono<String> requireUsername() {
        return findUsername().switchIfEmpty(Mono.error(ApiException.unauthorized()));
    }
}
