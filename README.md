# Pakkeleik med Strava API-et

Mange brukar Strava for å registrere ulike aktivitetar, alt frå springing til å hente juleøl i kjøleskapet. 
I tillegg til at Strava er ein fin plass å logge slike aktivitetar og dele dei med andre, så er også Strava ein fin 
plass for å leike litt med API-integrasjonar. Strava har ein moderne arkitektur og eit veldokumentert API. 
Vil du for eksempel hente ut data og integrere dei i din eigen applikasjon så er det enkelt å gjere. 
Du kan også lage di eiga opplasting av aktivitetar.

## Kort om testapplikasjonen

- Applikasjonen er ein [Micronaut-applikasjon](https://micronaut.io/) som implementerer ein reaktiv HTTP-server med [Reactor](https://projectreactor.io/)
- [OAuth 2.0](https://oauth.net/2/) er brukt for å autentisere med Strava
- [Micronaut HttpClient](https://guides.micronaut.io/latest/micronaut-http-client.html) er brukt for å kommunisere med Strava API-et
- Eksempel 1: Bla gjennom ei liste over aktivitetar for ein brukar
- Eksempel 2: Vis vekestatistikk for ein brukar

## Trinn 1: Registrere applikasjonen hos Strava

Det første som må gjerast er å [logge på Strava](https://www.strava.com/login), eventuelt [opprette ein ny brukar](https://www.strava.com/register).
Deretter må du [registrere applikasjonen hos Strava](https://www.strava.com/settings/api). 
Du får då ein **client ID** og ein **client secret** som må brukast i applikasjonen.

## Trinn 2: Eksempelapplikasjonen

Eksempelapplikasjonen er veldig enkel og består av fylgjande:

- Application.java: sjølve Micronaut-applikasjonen som startar opp HTTP-serveren
- StravaController.java: ein reaktiv REST-kontroller med administrative endepunkt som **/auth** og **/callback** (
  autentisering), **/health** (helsesjekk) og **/activities** og **/stats/weekly** (eksempel på henting av data frå
  Strava)
- StravaService.java: implementasjon av funksjonaliteteen i applikasjonen, inkludert autentisering og fornying av token
- StravaConfig.java: konfigurasjonsklasse for applikasjonen
- application.yml: konfugurasjon av applikasjonen, inkludert **client ID** og **client secret** for tilgang til Strava
  API

### Application

Alt som trengs for å starte applikasjonen er dette:

    public class Application {
        static void main(String[] args) {
            Micronaut.run(Application.class, args);
        }
    }

### StravaController

Dei ulike metodane i kontrolleren er bygd opp ganske likt og brukar Micronaut HTTP-annoteringar og Reactor for å
implementere eit reaktivt asynkront endepunkt. Det kan typisk sjå slik ut:

    @Get("/activities")
    public Mono<HttpResponse<Object>> getActivities(
        @QueryValue(defaultValue = "10") int perPage, 
        @QueryValue(defaultValue = "1") int page) {
        return stravaService.getActivities(perPage, page)
            .map(activities ->
                HttpResponse.<Object>ok(
                    Map.of(
                        "activities", activities,
                        "count", activities.size(),
                        "page", page,
                        "per_page", perPage)))
            .onErrorResume(IllegalStateException.class, 
                _ -> { return Mono.just(handleUnauthorized()); })
            .onErrorResume(e -> { return Mono.just(handleServerError(e)); })
            .map(response -> response);
    }

### StravaService

Autentisering skjer med ein standard OAuth 2.0-prosess der **client ID** og **client secret** vert veksla ut med *
*accessToken** og **refreshToken**. Det kan sjå slik ut:

    public Mono<Void> exchangeToken(String code) {
        var url = "https://www.strava.com/oauth/token";

        var requestBody = String.format(
            "client_id=%s&client_secret=%s" 
              + "&code=%s&grant_type=authorization_code",
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
                var json = new JSONObject(response);
                this.accessToken = json.getString("access_token");
                this.refreshToken = json.getString("refresh_token");
                this.tokenExpiresAt = json.getLong("expires_at");
            })
            .then();
    }

Deretter kan Strava-apiet brukast for å hente ut dei dataene ein er interessert i. Det kan sjå slik ut om ein vil hente
aktiviterar for ein brukar:

    public Mono<List<Map<String, Object>>> getActivities(int perPage, int page) {
        return ensureValidToken()
            .flatMap(token -> {
                var url = String.format(
                    "https://www.strava.com/api/v3/athlete/activities?" 
                      + "per_page=%d&page=%d",
                    perPage, page
                );
                return Mono.from(httpClient.retrieve(
                    io.micronaut.http.HttpRequest.GET(url)
                        .header("Authorization", "Bearer " + token),
                    String.class
                ));
            })
            .map(response -> {
                var activities = new JSONArray(response);
                List<Map<String, Object>> result = new ArrayList<>();

                for (int i = 0; i < activities.length(); i++) {
                    var activity = activities.getJSONObject(i);
                    Map<String, Object> activityMap = new HashMap<>();

                    activityMap.put("id", 
                        activity.getLong("id"));
                    activityMap.put("name", 
                        activity.getString("name"));
                    activityMap.put("type", 
                        activity.getString("type"));
                    activityMap.put("distance_km", 
                        activity.getDouble("distance") / 1000);
                    activityMap.put("moving_time_minutes", 
                        activity.getInt("moving_time") / 60);
                    activityMap.put("date", 
                        activity.getString("start_date"));
                    activityMap.put("device_name", 
                        activity.getString("device_name"));
                    activityMap.put("kudos_count", 
                        activity.getInt("kudos_count"));
                    activityMap.put("comment_count", 
                        activity.getInt("comment_count"));

                    if (activity.has("average_speed")) {
                        activityMap.put("average_speed_kmh", 
                            activity.getDouble("average_speed") * 3.6);
                    }
                    if (activity.has("total_elevation_gain")) {
                        activityMap.put("elevation_gain_m", 
                            activity.getDouble("total_elevation_gain"));
                    }

                    result.add(activityMap);
                }

                return result;
            });
    }

Det fins ei stor mengde andre API-endepunkt i [Strava API dokumentasjonen](https://developers.strava.com/docs/reference/).

## Trinn 3: Test av applikasjonen

Sidan eg primært er ein backend-utviklar så brukar eg gjerne [curl](https://curl.se/) (eller andre verktøy
som [Postman](https://www.postman.com/) eller [Bruno](https://www.usebruno.com/)) for å teste applikasjonen før den har eit brukargrensesnitt. 
Sidan  applikasjonen leverer JSON så er [jq](https://jqlang.org/) fin å bruke for å formatere resultatet.

Etter autentisering kan eg for eksempel hente ut aktivitetar for ein brukar med kommandoen

    curl http://localhost:8080/activities?perPage=5&page=1 | jq

og får ut eit resultat som dette:

    {
      "per_page": 5,
      "activities": [
        {
          "date": "2025-12-10T05:58:49Z",
          "comment_count": 0,
          "device_name": "Garmin Forerunner 955",
          "distance_km": 6.0378,
          "moving_time_minutes": 33,
          "elevation_gain_m": 45.0,
          "name": "Første rolige tur etter maraton",
          "average_speed_kmh": 10.9188,
          "id": 16700599462,
          "kudos_count": 5,
          "type": "Run"
        },
        {
          "date": "2025-12-07T07:15:48Z",
          "comment_count": 3,
          "device_name": "Garmin Forerunner 955",
          "distance_km": 42.469199999999994,
          "moving_time_minutes": 175,
          "elevation_gain_m": 64.0,
          "name": "Valencia Marathon 2:55:47 (1:27:11/1:28:36)",
          "average_speed_kmh": 14.493599999999999,
          "id": 16673938576,
          "kudos_count": 19,
          "type": "Run"
        },
        {
          "date": "2025-12-07T06:36:07Z",
          "comment_count": 0,
          "device_name": "Garmin Forerunner 955",
          "distance_km": 2.3546,
          "moving_time_minutes": 14,
          "elevation_gain_m": 2.0,
          "name": "Oppvarming til Valencia Marathon",
          "average_speed_kmh": 9.6768,
          "id": 16673937251,
          "kudos_count": 0,
          "type": "Run"
        },
        {
          "date": "2025-12-06T15:43:59Z",
          "comment_count": 0,
          "device_name": "Garmin Forerunner 955",
          "distance_km": 5.7138,
          "moving_time_minutes": 27,
          "elevation_gain_m": 20.0,
          "name": "Kort løpetur i Valencia",
          "average_speed_kmh": 12.549600000000002,
          "id": 16667456723,
          "kudos_count": 7,
          "type": "Run"
        },
        {
          "date": "2025-12-05T05:55:58Z",
          "comment_count": 4,
          "device_name": "Garmin Forerunner 955",
          "distance_km": 6.411899999999999,
          "moving_time_minutes": 32,
          "elevation_gain_m": 77.0,
          "name": "Siste tur før avreise til Valencia",
          "average_speed_kmh": 11.934,
          "id": 16654388538,
          "kudos_count": 6,
          "type": "Run"
        }
      ],
      "count": 5,
      "page": 1
    }

Eg kan også hente ut vekestatistikk for ein brukar med kommandoen

    curl http://localhost:8080/stats/weekly | jq

og få ut eit resultat som dette:

    {
      "summary": {
        "total_distance_km": 501.53,
        "total_activities": 53,
        "number_of_weeks": 5,
        "average_distance_per_week_km": 100.31
      },
      "weeks": [
        {
          "is_current_week": false,
          "distance_km": 146.96,
          "year": 2025,
          "activity_count": 14,
          "week_start": "2025-11-10",
          "week_end": "2025-11-16",
          "week_number": 46
        },
        {
          "is_current_week": false,
          "distance_km": 142.96,
          "year": 2025,
          "activity_count": 15,
          "week_start": "2025-11-17",
          "week_end": "2025-11-23",
          "week_number": 47
        },
        {
          "is_current_week": false,
          "distance_km": 110.11,
          "year": 2025,
          "activity_count": 13,
          "week_start": "2025-11-24",
          "week_end": "2025-11-30",
          "week_number": 48
        },
        {
          "is_current_week": false,
          "distance_km": 95.46,
          "year": 2025,
          "activity_count": 10,
          "week_start": "2025-12-01",
          "week_end": "2025-12-07",
          "week_number": 49
        },
        {
          "is_current_week": true,
          "distance_km": 6.04,
          "year": 2025,
          "activity_count": 1,
          "week_start": "2025-12-08",
          "week_end": "2025-12-14",
          "week_number": 50
        }
      ]
    }

## Teknologi bak Strava

Strava kjører primært på AWS og brukar teknologiar som MySQL, Redis, Cassandra, Snowflake og mange fleire. 
Serverside er utvikla i blant anna Scala, Ruby og Go. 
Dei har gått frå å ha ein stor monolitt til no å ha hundrevis av mikrotenester.

## Kva med å hente data rett frå Garmin Connect då?

Strava har jo typisk henta data frå andre kjelder som Garmin, Coros, Polar, Suunto, Apple Watch osv. 
Sjølv har eg mest kjennskap til Garmin, og kunne sånn sett henta tilsvarande data rett frå Garmin Connect. 
Det er imidlertid ikke så lett som for Strava:

- Ein må ha bedriftstilgang (ikkje tilgjengeleg for enkeltutviklarar eller testformål)
- Ein må gjennom ein godkjenningsprosess hos Garmin
- API-et er primært tenkt for kommersiell bruk
- Ein del data er lisensbelagt

## Meir informasjon

- [Strava API](https://developers.strava.com/docs/)
- [Micronaut User Guide](https://docs.micronaut.io/4.10.3/guide/index.html)
- [Applikasjonen i Github](https://github.com/frodeo/strava-application)
