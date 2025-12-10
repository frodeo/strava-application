package no.kraftlauget;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import jakarta.inject.Inject;
import java.util.Map;
import reactor.core.publisher.Mono;

@Controller
@Produces(MediaType.APPLICATION_JSON)
public class StravaController {

  @Inject private StravaService stravaService;

  @Get("/auth")
  public Mono<Map<String, String>> authenticate() {
    return Mono.fromCallable(
        () -> {
          var authUrl = stravaService.getAuthorizationUrl();
          return Map.of("message", "Gå til denne lenken for å autentisere", "auth_url", authUrl);
        });
  }

  @Get("/callback")
  public Mono<MutableHttpResponse<Map<String, String>>> callback(@QueryValue String code) {
    return stravaService
        .exchangeToken(code)
        .map(
            _ ->
                HttpResponse.ok(
                    Map.of(
                        "status", "success",
                        "message", "Autentisering vellykket! Du kan nå hente aktiviteter.")))
        .onErrorResume(
            e ->
                Mono.just(
                    HttpResponse.serverError(
                        Map.of(
                            "status",
                            "error",
                            "message",
                            "Feil ved autentisering: " + e.getMessage()))));
  }

  @Get("/activities")
  public Mono<HttpResponse<Object>> getActivities(
      @QueryValue(defaultValue = "10") int perPage, @QueryValue(defaultValue = "1") int page) {
    return stravaService
        .getActivities(perPage, page)
        .map(
            activities ->
                HttpResponse.<Object>ok(
                    Map.of(
                        "activities", activities,
                        "count", activities.size(),
                        "page", page,
                        "per_page", perPage)))
        .onErrorResume(
            IllegalStateException.class,
            _ -> {
              MutableHttpResponse<Object> response =
                  HttpResponse.unauthorized()
                      .body(
                          Map.of(
                              "status", "error",
                              "message",
                                  "Ikke autentisert. Gå til /auth for å starte autentisering."));
              return Mono.just(response);
            })
        .onErrorResume(
            e -> {
              MutableHttpResponse<Object> response =
                  HttpResponse.serverError(Map.of("status", "error", "message", e.getMessage()));
              return Mono.just(response);
            })
        .map(response -> response);
  }

  @Get("/stats/weekly")
  public Mono<HttpResponse<Object>> getWeeklyStats(@QueryValue(defaultValue = "12") int weeks) {
    return stravaService
        .getWeeklyStats(weeks)
        .map(stats -> HttpResponse.<Object>ok(stats))
        .onErrorResume(
            IllegalStateException.class,
            e -> {
              MutableHttpResponse<Object> response =
                  HttpResponse.<Object>unauthorized()
                      .body(
                          Map.of(
                              "status", "error",
                              "message",
                                  "Ikke autentisert. Gå til /auth for å starte autentisering."));
              return Mono.just(response);
            })
        .onErrorResume(
            e -> {
              MutableHttpResponse<Object> response =
                  HttpResponse.<Object>serverError(
                      Map.of("status", "error", "message", e.getMessage()));
              return Mono.just(response);
            })
        .map(response -> (HttpResponse<Object>) response);
  }

  @Get("/health")
  public Mono<Map<String, Object>> health() {
    return Mono.fromCallable(
        () -> {
          boolean authenticated = stravaService.isAuthenticated();
          return Map.of(
              "status", "ok",
              "authenticated", authenticated,
              "service", "strava-api");
        });
  }
}
