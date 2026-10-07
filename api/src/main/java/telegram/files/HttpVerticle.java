package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.healthchecks.HealthChecks;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.SessionHandler;
import io.vertx.ext.web.healthchecks.HealthCheckHandler;
import io.vertx.ext.web.sstore.LocalSessionStore;
import io.vertx.ext.web.sstore.SessionStore;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.function.Function2;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.SettingRecord;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class HttpVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    // ws text handler id -> telegram id the socket follows ("" = all accounts). Per socket, not per session:
    // with one slot per session cookie a second tab stole the first one's events, and a late close of the
    // previous socket unregistered its replacement.
    private static final Map<String, String> sockets = new ConcurrentHashMap<>();

    // session id -> telegram verticle
    private final Map<String, TelegramVerticle> sessionTelegramVerticles = new ConcurrentHashMap<>();

    private final FileRouteHandler fileRouteHandler = new FileRouteHandler();

    private static final String SESSION_COOKIE_NAME = "tf";

    @Override
    public void start(Promise<Void> startPromise) {
        // Automation scan cursors were only saved on a clean shutdown; a crash lost all progress.
        vertx.setPeriodic(60_000, _ -> AutomationsHolder.INSTANCE.saveAutoRecords());
        initHttpServer()
                .compose(_ -> initTelegramVerticles())
                .compose(_ -> AutomationsHolder.INSTANCE.init())
                .compose(_ -> initAutoDownloadVerticle())
                .compose(_ -> initTransferVerticle())
                .compose(_ -> initPreloadMessageVerticle())
                .compose(_ -> initEventConsumer())
                .onSuccess(startPromise::complete)
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        AutomationsHolder.INSTANCE.saveAutoRecords()
                .onComplete(ignore -> {
                    log.info("Http verticle stopped!");
                    stopPromise.complete();
                });
    }

    public Future<Void> initHttpServer() {
        int port = config().getInteger("http.port", 8080);
        HttpServerOptions options = new HttpServerOptions()
                .setLogActivity(true)
                .setRegisterWebSocketWriteHandlers(true)
                .setMaxWebSocketMessageSize(1024 * 1024)
                .setIdleTimeout(60)
                .setIdleTimeoutUnit(TimeUnit.SECONDS)
                .setPort(port);

        return vertx.createHttpServer(options)
                .requestHandler(initRouter())
                .listen()
                .onSuccess(_ -> log.info("API server started on port " + port))
                .onFailure(err -> log.error("Failed to start API server: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    public Router initRouter() {
        Router router = Router.router(vertx);

        SessionStore sessionStore = LocalSessionStore.create(vertx, SESSION_COOKIE_NAME);
        SessionHandler sessionHandler = SessionHandler.create(sessionStore)
                .setSessionCookieName(SESSION_COOKIE_NAME);
        if (Config.isProd()) {
            sessionHandler
                    .setCookieSameSite(CookieSameSite.STRICT);
        } else {
            sessionHandler
                    .setCookieSameSite(CookieSameSite.NONE)
                    .setCookieSecureFlag(true);
        }
        router.route()
                .handler(sessionHandler)
                // No upload endpoints: don't spool multipart bodies to ./file-uploads, cap JSON bodies.
                .handler(BodyHandler.create(false).setBodyLimit(8 * 1024 * 1024));

        if (!Config.isProd()) {
            router.route()
                    .handler(CorsHandler.create()
                            .addOrigin("http://localhost:3000")
                            .allowedMethod(HttpMethod.GET)
                            .allowedMethod(HttpMethod.POST)
                            .allowedMethod(HttpMethod.PUT)
                            .allowedMethod(HttpMethod.DELETE)
                            .allowedMethod(HttpMethod.OPTIONS)
                            .allowCredentials(true)
                            .allowedHeader("Access-Control-Request-Method")
                            .allowedHeader("Access-Control-Allow-Credentials")
                            .allowedHeader("Access-Control-Allow-Origin")
                            .allowedHeader("Access-Control-Allow-Headers")
                            .allowedHeader("Content-Type")
                    );
        }

        HealthChecks hc = HealthChecks.create(vertx);
        hc.register("http-server", Promise::complete);

        router.get("/").handler(ctx -> ctx.response().end("Hello World!"));
        router.get("/health").handler(HealthCheckHandler.createWithHealthChecks(hc));
        router.get("/version").handler(ctx -> ctx.json(new JsonObject().put("version", Start.VERSION)));
        router.route("/ws").handler(this::handleWebSocket);

        router.get("/settings").handler(this::handleSettings);
        router.post("/settings/create").handler(this::handleSettingsCreate);

        router.post("/telegram/create").handler(this::handleTelegramCreate);
        router.post("/telegram/:telegramId/delete").handler(this::handleTelegramDelete);
        router.get("/telegram/api/methods").handler(this::handleTelegramApiMethods);
        router.get("/telegram/api/:method/parameters").handler(this::handleTelegramApiMethodParameters);
        router.post("/telegram/api/:method").handler(this::handleTelegramApi);
        router.get("/telegrams").handler(this::handleTelegrams);
        router.get("/telegram/:telegramId/chats").handler(this::handleTelegramChats);
        router.get("/telegram/:telegramId/chat/:chatId/files").handler(this::handleTelegramFiles);
        router.get("/telegram/:telegramId/chat/:chatId/files/count").handler(this::handleTelegramFilesCount);
        router.get("/telegram/:telegramId/download-statistics").handler(this::handleTelegramDownloadStatistics);
        router.post("/telegrams/change").handler(this::handleTelegramChange);
        router.post("/telegram/:telegramId/toggle-proxy").handler(this::handleTelegramToggleProxy);
        router.get("/telegram/:telegramId/ping").handler(this::handleTelegramPing);
        router.get("/telegram/:telegramId/test-network").handler(this::handleTelegramTestNetwork);

        router.get("/:telegramId/file/:uniqueId").handler(this::handleFilePreview);
        router.post("/:telegramId/file/start-download").handler(this::handleFileStartDownload);
        router.post("/:telegramId/file/cancel-download").handler(this::handleFileCancelDownload);
        router.post("/:telegramId/file/toggle-pause-download").handler(this::handleFileTogglePauseDownload);
        router.post("/:telegramId/file/remove").handler(this::handleFileRemove);
        router.post("/:telegramId/file/update-auto-settings").handler(this::handleAutoSettingsUpdate);

        router.get("/files/count").handler(this::handleFilesCount);
        router.get("/files").handler(this::handleFiles);
        router.post("/files/start-download-multiple").handler(this::handleFileStartDownloadMultiple);
        router.post("/files/cancel-download-multiple").handler(this::handleFileCancelDownloadMultiple);
        router.post("/files/toggle-pause-download-multiple").handler(this::handleFileTogglePauseDownloadMultiple);
        router.post("/files/remove-multiple").handler(this::handleFileRemoveMultiple);
        router.post("/files/update-tags").handler(this::handleFileTagsUpdateMultiple);
        router.post("/file/:uniqueId/update-tags").handler(this::handleFileTagsUpdate);

        router.route()
                .failureHandler(ctx -> {
                    int statusCode = ctx.statusCode();
                    if (statusCode < 500) {
                        if (ctx.response().ended()) {
                            return;
                        }
                        ctx.response().setStatusCode(statusCode).end();
                        return;
                    }
                    Throwable throwable = ctx.failure();
                    log.trace("route: %s, statusCode: %d".formatted(
                            ctx.request().path(),
                            statusCode), throwable);
                    HttpServerResponse response = ctx.response();
                    response.setStatusCode(statusCode)
                            .putHeader("Content-Type", "application/json")
                            .end(JsonObject.of("error", throwable == null ? "☹️Sorry! Not today." : throwable.getMessage()).encode());
                });
        return router;
    }

    public Future<Void> initTelegramVerticles() {
        return TelegramVerticles.initTelegramVerticles(vertx);
    }

    public Future<Void> initAutoDownloadVerticle() {
        return vertx.deployVerticle(new AutoDownloadVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    public Future<Void> initTransferVerticle() {
        return vertx.deployVerticle(new TransferVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    public Future<Void> initPreloadMessageVerticle() {
        return vertx.deployVerticle(new PreloadMessageVerticle(), Config.VIRTUAL_THREAD_DEPLOYMENT_OPTIONS)
                .mapEmpty();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.TELEGRAM_EVENT.address(), message -> {
            if (sockets.isEmpty()) {
                return;
            }
            JsonObject jsonObject = (JsonObject) message.body();
            if (log.isTraceEnabled()) {
                log.trace("Received telegram event: {}", jsonObject);
            }
            String telegramId = jsonObject.getString("telegramId");
            String encoded = jsonObject.getJsonObject("payload").encode();
            sockets.forEach((wsHandlerId, followed) -> {
                if (followed.isEmpty() || followed.equals(telegramId)) {
                    vertx.eventBus().send(wsHandlerId, encoded);
                }
            });
        });

        vertx.eventBus().consumer(EventEnum.AUTO_DOWNLOAD_UPDATE.address(), message -> {
            log.debug("Auto settings update: %s".formatted(message.body()));
            AutomationsHolder.INSTANCE.onAutoRecordsUpdate(Json.decodeValue(message.body().toString(), SettingAutoRecords.class));
        });
        return Future.succeededFuture();
    }

    private void handleWebSocket(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        String telegramId = ctx.request().getParam("telegramId");
        ctx.request().toWebSocket()
                .onSuccess(ws -> {
                    log.debug("Upgraded to WebSocket. SessionId: %s".formatted(sessionId));
                    String handlerId = ws.textHandlerID();
                    if (!handleTelegramChange(sessionId, telegramId)) {
                        log.debug("Failed to change telegram verticle. SessionId: %s".formatted(sessionId));
                    }
                    if (handlerId != null) {
                        sockets.put(handlerId, StrUtil.nullToEmpty(telegramId));
                    }

                    long timerId = vertx.setPeriodic(30000, _ -> {
                        if (!ws.isClosed()) {
                            ws.writePing(Buffer.buffer("👀"));
                        }
                    });

                    ws.exceptionHandler(throwable -> log.debug("WebSocket error: %s".formatted(throwable.getMessage())));
                    ws.closeHandler(_ -> {
                        if (handlerId != null) {
                            sockets.remove(handlerId);
                        }
                        vertx.cancelTimer(timerId);
                        log.debug("WebSocket closed. SessionId: %s".formatted(sessionId));
                    });

                    ws.textMessageHandler(text -> log.debug("Received WebSocket message: " + text));
                })
                .onFailure(err -> log.warn("Failed to upgrade to WebSocket: %s".formatted(err.getMessage())));
    }

    private void handleSettingsCreate(RoutingContext ctx) {
        JsonObject object = ctx.body().asJsonObject();
        if (CollUtil.isEmpty(object)) {
            ctx.fail(400);
            return;
        }

        Future.all(object.stream()
                        .map(setting -> DataVerticle.settingRepository.createOrUpdate(setting.getKey(),
                                Convert.toStr(setting.getValue(), "")))
                        .toList())
                .map(CompositeFuture::<SettingRecord>list)
                .onSuccess(records -> {
                    records.forEach(record ->
                            vertx.eventBus().publish(EventEnum.SETTING_UPDATE.address(record.key()), record.value()));
                    ctx.end();
                })
                .onFailure(ctx::fail);
    }

    private void handleSettings(RoutingContext ctx) {
        String keysStr = ctx.request().getParam("keys");
        if (StrUtil.isBlank(keysStr)) {
            ctx.fail(400);
            return;
        }
        List<String> keys = Arrays.asList(keysStr.split(","));
        DataVerticle.settingRepository
                .getByKeys(keys)
                .onSuccess(settings -> {
                    JsonObject object = new JsonObject();
                    for (SettingRecord record : settings) {
                        object.put(record.key(), record.value());
                    }
                    for (String key : keys) {
                        if (object.containsKey(key)) {
                            continue;
                        }
                        object.put(key, SettingKey.valueOf(key).defaultValue);
                    }
                    ctx.json(object);
                })
                .onFailure(ctx::fail);
    }

    private void handleTelegramCreate(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = sessionTelegramVerticles.get(sessionId);
        boolean isClosedOrClosing = telegramVerticle != null &&
                telegramVerticle.lastAuthorizationState != null &&
                (telegramVerticle.lastAuthorizationState.getConstructor() == TdApi.AuthorizationStateClosed.CONSTRUCTOR ||
                        telegramVerticle.lastAuthorizationState.getConstructor() == TdApi.AuthorizationStateClosing.CONSTRUCTOR);
        if (telegramVerticle != null && !telegramVerticle.authorized && !isClosedOrClosing) {
            ctx.json(new JsonObject()
                    .put("id", telegramVerticle.getId())
                    .put("lastState", telegramVerticle.lastAuthorizationState)
            );
            return;
        }
        JsonObject jsonObject = ctx.body().asJsonObject();
        String proxyName = jsonObject.getString("proxyName");

        TelegramVerticle newTelegramVerticle = new TelegramVerticle(DataVerticle.telegramRepository.getRootPath());
        newTelegramVerticle.setProxy(proxyName);
        sessionTelegramVerticles.put(sessionId, newTelegramVerticle);
        TelegramVerticles.add(newTelegramVerticle);
        vertx.deployVerticle(newTelegramVerticle)
                .onSuccess(_ -> ctx.json(new JsonObject()
                        .put("id", newTelegramVerticle.getId())
                        .put("lastState", newTelegramVerticle.lastAuthorizationState)
                ))
                .onFailure(ctx::fail);
    }

    private void handleTelegramDelete(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        telegramVerticle.close(true)
                .onSuccess(_ -> {
                    TelegramVerticles.remove(telegramVerticle);
                    sessionTelegramVerticles.entrySet().removeIf(e -> e.getValue().equals(telegramVerticle));
                    ctx.end();
                });
    }

    private void handleTelegrams(RoutingContext ctx) {
        Boolean authorized = Convert.toBool(ctx.request().getParam("authorized"));
        Future.all(TelegramVerticles.getAll().stream()
                        .filter(c -> authorized == null || c.authorized == authorized)
                        .map(TelegramVerticle::getTelegramAccount)
                        .toList()
                )
                .map(CompositeFuture::list)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChats(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String query = ctx.request().getParam("query");
        String chatId = ctx.request().getParam("chatId");
        String archived = ctx.request().getParam("archived");
        telegramVerticle.getChats(Convert.toLong(chatId), query, Convert.toBool(archived, false))
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramFiles(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String chatId = ctx.pathParam("chatId");
        if (StrUtil.isBlank(chatId)) {
            ctx.fail(400);
            return;
        }
        String link = URLUtil.decode(ctx.queryParams().get("link"));
        if (StrUtil.isNotBlank(link)) {
            telegramVerticle.parseLink(link)
                    .onSuccess(ctx::json)
                    .onFailure(ctx::fail);
            return;
        }

        Map<String, String> filter = new HashMap<>();
        ctx.request().params().forEach(filter::put);
        filter.put("search", URLUtil.decode(filter.get("search")));

        telegramVerticle.getChatFiles(Convert.toLong(chatId), filter)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramFilesCount(RoutingContext ctx) {
        boolean offline = Convert.toBool(ctx.queryParams().get("offline"), false);
        Long telegramId = Convert.toLong(ctx.pathParam("telegramId"), -1L);
        Long chatId = Convert.toLong(ctx.pathParam("chatId"), -1L);
        if (offline) {
            DataVerticle.fileRepository.countWithType(telegramId, chatId)
                    .onSuccess(ctx::json)
                    .onFailure(ctx::fail);
            return;
        }

        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        telegramVerticle.getChatFilesCount(chatId)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramDownloadStatistics(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }

        String type = ctx.request().getParam("type");
        String timeRange = ctx.request().getParam("timeRange");
        (Objects.equals(type, "phase") ? telegramVerticle.getDownloadStatisticsByPhase(Convert.toInt(timeRange, 1)) :
                telegramVerticle.getDownloadStatistics())
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleTelegramChange(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        String telegramId = ctx.request().getParam("telegramId");
        if (handleTelegramChange(sessionId, telegramId)) {
            ctx.end();
        } else {
            ctx.fail(400);
        }
    }

    private boolean handleTelegramChange(String sessionId, String telegramId) {
        if (StrUtil.isBlank(telegramId)) {
            sessionTelegramVerticles.remove(sessionId);
            return true;
        }
        Optional<TelegramVerticle> optionalTelegramVerticle = TelegramVerticles.get(telegramId);
        if (optionalTelegramVerticle.isEmpty()) {
            return false;
        }
        sessionTelegramVerticles.put(sessionId, optionalTelegramVerticle.get());
        return true;
    }

    private void handleTelegramToggleProxy(RoutingContext ctx) {
        String telegramId = ctx.request().getParam("telegramId");
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                        telegramVerticle.toggleProxy(ctx.body().asJsonObject())
                                .onSuccess(r -> ctx.json(JsonObject.of("proxy", r)))
                                .onFailure(ctx::fail), () -> ctx.fail(404));
    }

    private void handleTelegramPing(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                        telegramVerticle.ping()
                                .onSuccess(r -> ctx.json(JsonObject.of("ping", r)))
                                .onFailure(ctx::fail), () -> ctx.fail(404)
                );
    }

    private void handleTelegramTestNetwork(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return;
        }
        TelegramVerticles.get(telegramId)
                .ifPresentOrElse(telegramVerticle ->
                                telegramVerticle.client.execute(new TdApi.TestNetwork(), 10000, vertx)
                                        .onComplete(r ->
                                                ctx.json(JsonObject.of("success", r.succeeded()))),
                        () -> ctx.fail(404)
                );
    }

    private void handleTelegramApiMethods(RoutingContext ctx) {
        Map<String, Class<TdApi.Function<?>>> functions = TdApiHelp.getFunctions();
        ctx.json(JsonObject.of("methods", functions.keySet()));
    }

    private void handleTelegramApiMethodParameters(RoutingContext ctx) {
        String method = ctx.pathParam("method");
        ctx.json(JsonObject.of("parameters", TdApiHelp.getFunction(method, null)));
    }

    private void handleTelegramApi(RoutingContext ctx) {
        String method = ctx.pathParam("method");
        if (method == null) {
            ctx.fail(400);
            return;
        }
        TelegramVerticle telegramVerticle = getTelegramVerticleBySession(ctx);
        if (telegramVerticle == null) {
            return;
        }
        JsonObject params = ctx.body().asJsonObject();
        telegramVerticle.execute(method, params == null ? null : params.getMap())
                .onSuccess(code -> ctx.json(JsonObject.of("code", code)))
                .onFailure(ctx::fail);
    }

    private void handleFilePreview(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = getTelegramVerticleByPath(ctx);
        if (telegramVerticle == null) {
            return;
        }
        String uniqueId = ctx.pathParam("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.fail(404);
            return;
        }

        telegramVerticle.loadPreview(uniqueId)
                .onSuccess(tuple -> {
                    String mimeType = tuple.v2;
                    if (StrUtil.isBlank(mimeType)) {
                        mimeType = FileUtil.getMimeType(tuple.v1);
                    }

                    fileRouteHandler.handle(ctx, tuple.v1, mimeType);
                })
                .onFailure(ctx::fail);
    }

    private void handleFileStartDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Long chatId = jsonObject.getLong("chatId");
        Long messageId = jsonObject.getLong("messageId");
        Integer fileId = jsonObject.getInteger("fileId");
        if (chatId == null || messageId == null || fileId == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.startDownload(chatId, messageId, fileId)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFileCancelDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        if (fileId == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.cancelDownload(fileId)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileTogglePauseDownload(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        Boolean isPaused = jsonObject.getBoolean("isPaused");
        if (fileId == null || isPaused == null) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.togglePauseDownload(fileId, isPaused)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileRemove(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));
        if (telegramVerticle == null) {
            return;
        }

        JsonObject jsonObject = ctx.body().asJsonObject();
        Integer fileId = jsonObject.getInteger("fileId");
        String uniqueId = jsonObject.getString("uniqueId");
        if (fileId == null && StrUtil.isBlank(uniqueId)) {
            ctx.fail(400);
            return;
        }

        telegramVerticle.removeFile(fileId, uniqueId)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFileStartDownloadMultiple(RoutingContext ctx) {
        handleFileMultiple(ctx, (telegramVerticle, file) -> {
            Long chatId = file.getLong("chatId");
            Long messageId = file.getLong("messageId");
            Integer fileId = file.getInteger("fileId");
            if (chatId == null || messageId == null || fileId == null) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.startDownload(chatId, messageId, fileId);
        });
    }

    private void handleFileCancelDownloadMultiple(RoutingContext ctx) {
        handleFileMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            if (fileId == null) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.cancelDownload(fileId);
        });
    }

    private void handleFileTogglePauseDownloadMultiple(RoutingContext ctx) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        Boolean isPaused = jsonObject.getBoolean("isPaused");
        if (isPaused == null) {
            ctx.fail(400);
            return;
        }

        handleFileMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            if (fileId == null) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.togglePauseDownload(fileId, isPaused);
        });
    }

    private void handleFileRemoveMultiple(RoutingContext ctx) {
        handleFileMultiple(ctx, (telegramVerticle, file) -> {
            Integer fileId = file.getInteger("fileId");
            String uniqueId = file.getString("uniqueId");
            if (fileId == null && StrUtil.isBlank(uniqueId)) {
                return Future.failedFuture("Invalid parameters");
            }
            return telegramVerticle.removeFile(fileId, uniqueId);
        });
    }

    private void handleFileTagsUpdateMultiple(RoutingContext ctx) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        String tags = jsonObject.getString("tags");
        if (StrUtil.isBlank(tags)) {
            ctx.fail(400);
            return;
        }
        handleFileMultiple(ctx, (_, file) -> {
            String uniqueId = file.getString("uniqueId");
            if (StrUtil.isBlank(uniqueId)) {
                return Future.failedFuture("Invalid parameters");
            }
            return DataVerticle.fileRepository.updateTags(uniqueId, tags);
        });
    }

    private void handleFileMultiple(RoutingContext ctx, Function2<TelegramVerticle, JsonObject, Future<?>> handler) {
        JsonObject jsonObject = ctx.body().asJsonObject();
        JsonArray files = jsonObject.getJsonArray("files");
        if (CollUtil.isEmpty(files)) {
            ctx.fail(400);
            return;
        }
        Map<Long, List<Object>> groupingByTelegramId = files.stream()
                .collect(Collectors.groupingBy(f -> ((JsonObject) f).getLong("telegramId")));

        Future.all(groupingByTelegramId.entrySet()
                        .stream()
                        .flatMap(entry -> {
                            TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(entry.getKey());

                            return entry.getValue().stream()
                                    .map(f -> {
                                        JsonObject file = (JsonObject) f;
                                        return handler.apply(telegramVerticle, file);
                                    });
                        })
                        .toList()
                )
                .onSuccess(ctx::json).onFailure(r -> {
                    log.error(r, "Failed to handle multiple files: %s".formatted(r.getMessage()));
                    ctx.response()
                            .setStatusCode(400)
                            .end(JsonObject.of("error", "Part of the files failed to process: %s".formatted(r.getMessage())).encode());
                });
    }

    private void handleAutoSettingsUpdate(RoutingContext ctx) {
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(ctx.pathParam("telegramId"));

        String chatId = ctx.request().getParam("chatId");
        if (StrUtil.isBlank(chatId)) {
            ctx.fail(400);
            return;
        }
        JsonObject params = ctx.body().asJsonObject();
        telegramVerticle.updateAutoSettings(Convert.toLong(chatId), params)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private void handleFilesCount(RoutingContext ctx) {
        DataVerticle.fileRepository.getDownloadStatistics()
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFiles(RoutingContext ctx) {
        Map<String, String> filter = new HashMap<>();
        ctx.request().params().forEach(filter::put);
        filter.put("search", URLUtil.decode(filter.get("search")));

        FileRecordRetriever.getFiles(0, filter)
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }

    private void handleFileTagsUpdate(RoutingContext ctx) {
        String uniqueId = ctx.pathParam("uniqueId");
        if (StrUtil.isBlank(uniqueId)) {
            ctx.fail(400);
            return;
        }

        JsonObject params = ctx.body().asJsonObject();
        String tags = params.getString("tags");
        DataVerticle.fileRepository.updateTags(uniqueId, tags)
                .onSuccess(_ -> ctx.end())
                .onFailure(ctx::fail);
    }

    private TelegramVerticle getTelegramVerticleBySession(RoutingContext ctx) {
        String sessionId = ctx.session().id();
        TelegramVerticle telegramVerticle = sessionTelegramVerticles.get(sessionId);
        if (telegramVerticle == null) {
            ctx.response().setStatusCode(400)
                    .end(JsonObject.of("error", "Your session not link any telegram!").encode());
            return null;
        }
        return telegramVerticle;
    }

    private TelegramVerticle getTelegramVerticleByPath(RoutingContext ctx) {
        String telegramId = ctx.pathParam("telegramId");
        if (StrUtil.isBlank(telegramId)) {
            ctx.fail(400);
            return null;
        }
        Optional<TelegramVerticle> telegramVerticleOptional = TelegramVerticles.get(telegramId);
        if (telegramVerticleOptional.isEmpty()) {
            ctx.fail(404);
            return null;
        }
        return telegramVerticleOptional.get();
    }
}
