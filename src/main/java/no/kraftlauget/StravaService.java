package no.kraftlauget;

import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import jakarta.inject.Singleton;
import jakarta.inject.Inject;
import org.json.JSONArray;
import org.json.JSONObject;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;

@Singleton
public class StravaService {

    private final HttpClient httpClient;
    private final StravaConfig config;

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile long tokenExpiresAt;

    @Inject
    public StravaService(@Client HttpClient httpClient, StravaConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    public String getAuthorizationUrl() {
        return String.format(
                "https://www.strava.com/oauth/authorize?client_id=%s&redirect_uri=%s&response_type=code&scope=activity:read_all",
                config.getClientId(),
                config.getRedirectUri()
        );
    }

    public Mono<Void> exchangeToken(String code) {
        String url = "https://www.strava.com/oauth/token";

        String requestBody = String.format(
                "client_id=%s&client_secret=%s&code=%s&grant_type=authorization_code",
                config.getClientId(),
                config.getClientSecret(),
                code
        );

        return Mono.from(httpClient.retrieve(
                        io.micronaut.http.HttpRequest.POST(url, requestBody)
                                .contentType("application/x-www-form-urlencoded"),
                        String.class
                ))
                .doOnNext(response -> {
                    JSONObject json = new JSONObject(response);
                    this.accessToken = json.getString("access_token");
                    this.refreshToken = json.getString("refresh_token");
                    this.tokenExpiresAt = json.getLong("expires_at");
                })
                .then();
    }

    public Mono<List<Map<String, Object>>> getActivities(int perPage, int page) {
        return ensureValidToken()
                .flatMap(token -> {
                    String url = String.format(
                            "https://www.strava.com/api/v3/athlete/activities?per_page=%d&page=%d",
                            perPage, page
                    );

                    return Mono.from(httpClient.retrieve(
                            io.micronaut.http.HttpRequest.GET(url)
                                    .header("Authorization", "Bearer " + token),
                            String.class
                    ));
                })
                .map(response -> {
                    JSONArray activities = new JSONArray(response);
                    List<Map<String, Object>> result = new ArrayList<>();

                    for (int i = 0; i < activities.length(); i++) {
                        JSONObject activity = activities.getJSONObject(i);
                        Map<String, Object> activityMap = new HashMap<>();

                        activityMap.put("id", activity.getLong("id"));
                        activityMap.put("name", activity.getString("name"));
                        activityMap.put("type", activity.getString("type"));
                        activityMap.put("distance_km", activity.getDouble("distance") / 1000);
                        activityMap.put("moving_time_minutes", activity.getInt("moving_time") / 60);
                        activityMap.put("date", activity.getString("start_date"));
                        activityMap.put("device_name", activity.getString("device_name"));
                        activityMap.put("kudos_count", activity.getInt("kudos_count"));
                        activityMap.put("comment_count", activity.getInt("comment_count"));
                        if (activity.has("average_speed")) {
                            activityMap.put("average_speed_kmh", activity.getDouble("average_speed") * 3.6);
                        }
                        if (activity.has("total_elevation_gain")) {
                            activityMap.put("elevation_gain_m", activity.getDouble("total_elevation_gain"));
                        }

                        result.add(activityMap);
                    }

                    return result;
                });
    }

    public Mono<Map<String, Object>> getWeeklyStats(int numberOfWeeks) {
        return ensureValidToken()
                .flatMap(token -> fetchAllRecentActivities(token, numberOfWeeks))
                .map(activities -> calculateWeeklyStats(activities, numberOfWeeks));
    }

    private Mono<List<JSONObject>> fetchAllRecentActivities(String token, int numberOfWeeks) {
        long after = Instant.now()
                .minus(numberOfWeeks * 7L, ChronoUnit.DAYS)
                .getEpochSecond();

        List<JSONObject> allActivities = new ArrayList<>();
        return fetchActivitiesRecursive(token, after, 1, 200, allActivities);
    }

    private Mono<List<JSONObject>> fetchActivitiesRecursive(
            String token, long after, int page, int perPage, List<JSONObject> accumulated) {

        String url = String.format(
                "https://www.strava.com/api/v3/athlete/activities?after=%d&per_page=%d&page=%d",
                after, perPage, page
        );

        return Mono.from(httpClient.retrieve(
                        io.micronaut.http.HttpRequest.GET(url)
                                .header("Authorization", "Bearer " + token),
                        String.class
                ))
                .flatMap(response -> {
                    JSONArray activities = new JSONArray(response);

                    for (int i = 0; i < activities.length(); i++) {
                        accumulated.add(activities.getJSONObject(i));
                    }

                    if (activities.length() == perPage) {
                        return fetchActivitiesRecursive(token, after, page + 1, perPage, accumulated);
                    }

                    return Mono.just(accumulated);
                });
    }

    private Map<String, Object> calculateWeeklyStats(List<JSONObject> activities, int numberOfWeeks) {
        WeekFields weekFields = WeekFields.of(Locale.getDefault());
        LocalDate today = LocalDate.now();

        Map<String, WeekStats> weekStatsMap = new TreeMap<>();

        for (int i = numberOfWeeks - 1; i >= 0; i--) {
            LocalDate weekStart = today.minus(i * 7L, ChronoUnit.DAYS)
                    .with(weekFields.dayOfWeek(), 1);
            String weekKey = weekStart.toString();
            weekStatsMap.put(weekKey, new WeekStats(weekStart, 0.0, 0));
        }

        for (JSONObject activity : activities) {
            String startDate = activity.getString("start_date");
            LocalDate activityDate = LocalDate.parse(startDate.substring(0, 10));

            LocalDate weekStart = activityDate.with(weekFields.dayOfWeek(), 1);
            String weekKey = weekStart.toString();

            if (weekStatsMap.containsKey(weekKey)) {
                double distanceKm = activity.getDouble("distance") / 1000.0;
                WeekStats stats = weekStatsMap.get(weekKey);
                stats.totalDistance += distanceKm;
                stats.activityCount++;
            }
        }

        List<Map<String, Object>> weeks = new ArrayList<>();
        double totalDistance = 0.0;
        int totalActivities = 0;

        for (WeekStats stats : weekStatsMap.values()) {
            Map<String, Object> weekData = new HashMap<>();
            weekData.put("week_start", stats.weekStart.toString());
            weekData.put("week_end", stats.weekStart.plus(6, ChronoUnit.DAYS).toString());
            weekData.put("week_number", stats.weekStart.get(weekFields.weekOfWeekBasedYear()));
            weekData.put("year", stats.weekStart.getYear());
            weekData.put("distance_km", Math.round(stats.totalDistance * 100.0) / 100.0);
            weekData.put("activity_count", stats.activityCount);
            weekData.put("is_current_week", stats.weekStart.equals(
                    today.with(weekFields.dayOfWeek(), 1)
            ));

            weeks.add(weekData);
            totalDistance += stats.totalDistance;
            totalActivities += stats.activityCount;
        }

        double avgDistancePerWeek = totalDistance / numberOfWeeks;

        Map<String, Object> result = new HashMap<>();
        result.put("weeks", weeks);
        result.put("summary", Map.of(
                "total_distance_km", Math.round(totalDistance * 100.0) / 100.0,
                "total_activities", totalActivities,
                "average_distance_per_week_km", Math.round(avgDistancePerWeek * 100.0) / 100.0,
                "number_of_weeks", numberOfWeeks
        ));

        return result;
    }

    public boolean isAuthenticated() {
        return accessToken != null;
    }

    private Mono<String> ensureValidToken() {
        if (accessToken == null) {
            return Mono.error(new IllegalStateException("Ikke autentisert"));
        }

        if (Instant.now().getEpochSecond() >= tokenExpiresAt) {
            return refreshAccessToken();
        }

        return Mono.just(accessToken);
    }

    private Mono<String> refreshAccessToken() {
        String url = "https://www.strava.com/oauth/token";

        String requestBody = String.format(
                "client_id=%s&client_secret=%s&grant_type=refresh_token&refresh_token=%s",
                config.getClientId(),
                config.getClientSecret(),
                refreshToken
        );

        return Mono.from(httpClient.retrieve(
                        io.micronaut.http.HttpRequest.POST(url, requestBody)
                                .contentType("application/x-www-form-urlencoded"),
                        String.class
                ))
                .doOnNext(response -> {
                    JSONObject json = new JSONObject(response);
                    this.accessToken = json.getString("access_token");
                    this.refreshToken = json.getString("refresh_token");
                    this.tokenExpiresAt = json.getLong("expires_at");
                })
                .map(_ -> this.accessToken);
    }

    private static class WeekStats {
        LocalDate weekStart;
        double totalDistance;
        int activityCount;

        WeekStats(LocalDate weekStart, double totalDistance, int activityCount) {
            this.weekStart = weekStart;
            this.totalDistance = totalDistance;
            this.activityCount = activityCount;
        }
    }}
