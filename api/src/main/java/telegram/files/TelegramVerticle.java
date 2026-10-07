package telegram.files;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.VertxException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple2;
import telegram.files.repository.*;

import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public class TelegramVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    public TelegramClient client;

    private TelegramChats telegramChats;

    public volatile boolean authorized = false;

    public volatile TdApi.AuthorizationState lastAuthorizationState;

    public String rootPath;

    private String proxyName;

    private String rootId;

    private boolean needDelete = false;

    public volatile TelegramRecord telegramRecord;

    private AvgSpeed avgSpeed = new AvgSpeed();

    private long avgSpeedPersistenceTimerId;

    private long downloadStatusReconciliationTimerId;

    private volatile TdApi.ConnectionState lastConnectionState;

    private long lastFileDownloadEventTime;

    // The maps below are only touched on this verticle's context (TDLib updates are dispatched onto it).

    // fileId -> last time a progress event for that file was pushed to the UI
    private final Map<Integer, Long> lastFileEventTimes = new HashMap<>();

    // files the database already records as downloading: their progress ticks skip the database
    private final Set<Integer> downloadingFileIds = new HashSet<>();

    // files TDLib reported paused (updateFileDownload). Kept in update order, so a racing database write
    // of "idle"/"downloading" can't undo a pause.
    private final Set<Integer> pausedFileIds = new HashSet<>();

    public TelegramVerticle(String rootPath) {
        this.rootPath = rootPath;
    }

    public TelegramVerticle(TelegramRecord telegramRecord) {
        this.telegramRecord = telegramRecord;
        this.rootPath = telegramRecord.rootPath();
        this.proxyName = telegramRecord.proxy();
    }

    public String getRootId() {
        if (StrUtil.isNotBlank(this.rootId)) return rootId;

        this.rootId = StrUtil.subAfter(this.rootPath, '-', true);
        return this.rootId;
    }

    public Object getId() {
        return telegramRecord == null ? this.getRootId() : telegramRecord.id();
    }

    public void setProxy(String proxyName) {
        this.proxyName = proxyName;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        client = new TelegramClient();
        telegramChats = new TelegramChats(client);
        TelegramUpdateHandler telegramUpdateHandler = new TelegramUpdateHandler();
        telegramUpdateHandler.setOnAuthorizationStateUpdated(this::onAuthorizationStateUpdated);
        telegramUpdateHandler.setOnFileUpdated(this::onFileUpdated);
        telegramUpdateHandler.setOnFileDownloadUpdated(this::onFileDownloadUpdated);
        telegramUpdateHandler.setOnFileDownloadsUpdated(this::onFileDownloadsUpdated);
        telegramUpdateHandler.setOnChatUpdated(telegramChats::onChatUpdated);
        telegramUpdateHandler.setOnMessageReceived(this::onMessageReceived);
        telegramUpdateHandler.setOnConnectionStateUpdated(this::onConnectionStateUpdated);

        // Handle updates on this verticle's context rather than TDLib's single native thread: state is then
        // confined to one thread, and the first update can't arrive before initialize() has returned.
        Context ctx = context;
        client.initialize(update -> ctx.runOnContext(_ -> telegramUpdateHandler.onResult(update)),
                this::handleException, this::handleException);
        Future.all(initEventConsumer(), initAvgSpeed())
                .compose(_ -> this.enableProxy(this.proxyName))
                .compose(_ -> this.initDownloadStatusReconciliation())
                .onSuccess(_ -> startPromise.complete())
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        this.close(false)
                .onComplete(stopPromise);
    }

    public Future<Void> close(boolean needDelete) {
        if (downloadStatusReconciliationTimerId != 0) {
            vertx.cancelTimer(downloadStatusReconciliationTimerId);
            downloadStatusReconciliationTimerId = 0;
        }
        return client.execute(new TdApi.Close())
                .onSuccess(_ -> {
                    log.info("[%s] Telegram account closed".formatted(this.getRootId()));
                    this.needDelete = needDelete;
                })
                .onFailure(e -> log.error("[%s] Failed to close telegram account: %s".formatted(this.getRootId(), e.getMessage())))
                .mapEmpty();
    }

    public boolean check() {
        if (StrUtil.isBlank(this.rootPath) || !FileUtil.exist(this.rootPath)) {
            log.error("[%s] Telegram account is invalid, root path: %s not exist.".formatted(this.getRootId(), this.rootPath));
            return false;
        }
        return true;
    }

    public Future<JsonObject> getTelegramAccount() {
        return Future.future(promise -> {
            if (!authorized) {
                JsonObject jsonObject = new JsonObject()
                        .put("id", this.getRootId())
                        .put("name", this.getRootId())
                        .put("phoneNumber", "")
                        .put("avatar", "")
                        .put("status", "inactive")
                        .put("rootPath", this.rootPath)
                        .put("isPremium", false)
                        .put("lastAuthorizationState", lastAuthorizationState)
                        .put("proxy", this.proxyName);
                if (this.telegramRecord != null) {
                    jsonObject.put("id", Convert.toStr(this.telegramRecord.id()))
                            .put("name", this.telegramRecord.firstName());
                }
                promise.complete(jsonObject);
                return;
            }
            client.execute(new TdApi.GetMe())
                    .onSuccess(user -> {
                        JsonObject result = new JsonObject()
                                .put("id", Convert.toStr(user.id))
                                .put("name", StrUtil.join(user.firstName, " ", user.lastName))
                                .put("phoneNumber", user.phoneNumber)
                                .put("avatar", Base64.encode((byte[]) BeanUtil.getProperty(user, "profilePhoto.minithumbnail.data")))
                                .put("status", "active")
                                .put("rootPath", this.rootPath)
                                .put("isPremium", user.isPremium)
                                .put("proxy", this.proxyName);
                        promise.complete(result);
                    })
                    .onFailure(e -> {
                        log.error("[%s] Failed to get telegram account: %s".formatted(this.getRootId(), e.getMessage()));
                        promise.fail(e);
                    });
        });
    }

    public Future<JsonArray> getChats(Long activatedChatId, String query, boolean archived) {
        return TelegramConverter.convertChat(this.telegramRecord.id(), telegramChats.getChatList(activatedChatId, query, 100, archived));
    }

    public TdApi.Chat getChat(long chatId) {
        return telegramChats.getChat(chatId);
    }

    public Future<JsonObject> getChatFiles(long chatId, Map<String, String> filter) {
        boolean offline = Convert.toBool(filter.get("offline"), false);
        if (offline) {
            return FileRecordRetriever.getFiles(chatId, filter);
        } else {
            long messageThreadId = Convert.toLong(filter.get("messageThreadId"), 0L);
            TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
            searchChatMessages.chatId = chatId;
            searchChatMessages.query = filter.get("search");
            searchChatMessages.fromMessageId = Convert.toLong(filter.get("fromMessageId"), 0L);
            searchChatMessages.offset = Convert.toInt(filter.get("offset"), 0);
            searchChatMessages.limit = Convert.toInt(filter.get("limit"), 20);
            searchChatMessages.filter = TdApiHelp.getSearchMessagesFilter(filter.get("type"));
            searchChatMessages.topicId = messageThreadId > 0 ? new TdApi.MessageTopicThread(messageThreadId) : null;

            return (Objects.equals(filter.get("downloadStatus"), FileRecord.DownloadStatus.idle.name()) ?
                    this.getIdleChatFiles(searchChatMessages, 0) :
                    client.execute(searchChatMessages))
                    .compose(t -> {
                        preloadThumbnails(t);
                        return TelegramConverter.convertFiles(this.telegramRecord.id(), t);
                    });
        }
    }

    private Future<TdApi.FoundChatMessages> getIdleChatFiles(TdApi.SearchChatMessages searchChatMessages, int seq) {
        if (seq != 0) {
            // Increase the limit and reduce the number of requests
            searchChatMessages.limit = 100;
        }
        return client.execute(searchChatMessages)
                .compose(foundChatMessages -> {
                    TdApi.Message[] messages = Stream.of(foundChatMessages.messages)
                            .filter(message ->
                                    TdApiHelp.getFileHandler(message)
                                            .map(TdApiHelp.FileHandler::getFile)
                                            .map(file -> file.local == null || (
                                                    !file.local.isDownloadingActive
                                                    && !file.local.isDownloadingCompleted
                                                    && file.local.downloadedSize == 0
                                            ))
                                            .orElse(false)
                            )
                            .toArray(TdApi.Message[]::new);
                    if (ArrayUtil.isEmpty(messages) && foundChatMessages.nextFromMessageId != 0) {
                        searchChatMessages.fromMessageId = foundChatMessages.nextFromMessageId;
                        return getIdleChatFiles(searchChatMessages, seq + 1);
                    } else {
                        foundChatMessages.messages = messages;
                        return Future.succeededFuture(foundChatMessages);
                    }
                });
    }

    public Future<JsonObject> getChatFilesCount(long chatId) {
        return Future.all(
                Stream.of(new TdApi.SearchMessagesFilterPhotoAndVideo(),
                                new TdApi.SearchMessagesFilterPhoto(),
                                new TdApi.SearchMessagesFilterVideo(),
                                new TdApi.SearchMessagesFilterAudio(),
                                new TdApi.SearchMessagesFilterDocument())
                        .map(filter -> client.execute(
                                                new TdApi.GetChatMessageCount(chatId,
                                                        null,
                                                        filter,
                                                        false)
                                        )
                                        .map(count -> new JsonObject()
                                                .put("type", TdApiHelp.getSearchMessagesFilterType(filter))
                                                .put("count", count.count)
                                        )
                        )
                        .toList()
        ).map(counts -> {
            JsonObject result = new JsonObject();
            counts.<JsonObject>list().forEach(count -> result.put(count.getString("type"), count.getInteger("count")));
            return result;
        });
    }

    public Future<JsonObject> parseLink(String link) {
        return client.execute(new TdApi.GetMessageLinkInfo(link))
                .compose(messageLinkInfo -> {
                    if (messageLinkInfo.message == null) {
                        return Future.failedFuture("Message not found for link: " + link);
                    }
                    return FileRecordRetriever.getAlbumMessages(this.telegramRecord.id(), messageLinkInfo.message);
                })
                .compose(messages -> TelegramConverter.convertFiles(this.telegramRecord.id(), messages)
                        .map(files -> new JsonObject()
                                .put("files", files)
                                .put("count", files.size())
                                .put("size", files.size())
                                .put("nextFromMessageId", 0L) // No next message ID for link parsing
                        ));
    }

    public Future<Tuple2<String, String>> loadPreview(String uniqueId) {
        return DataVerticle.fileRepository
                .getByUniqueId(uniqueId)
                .compose(fileRecord -> {
                    if (fileRecord == null || !fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)
                        || !FileUtil.exist(fileRecord.localPath())) {
                        return Future.failedFuture("File not found or not downloaded");
                    }
                    return Future.succeededFuture(Tuple.tuple(fileRecord.localPath(), fileRecord.mimeType()));
                });
    }

    public Future<FileRecord> startDownload(Long chatId, Long messageId, Integer fileId) {
        return Future.all(
                        client.execute(new TdApi.GetFile(fileId)),
                        client.execute(new TdApi.GetMessage(chatId, messageId)),
                        client.execute(new TdApi.GetMessageThread(chatId, messageId), true)
                )
                .compose(results -> {
                    TdApi.File file = results.resultAt(0);
                    return DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                            .map(fileRecord -> Tuple.tuple(file,
                                    results.<TdApi.Message>resultAt(1),
                                    results.<TdApi.MessageThreadInfo>resultAt(2),
                                    fileRecord
                            ));
                })
                .compose(results -> {
                    TdApi.File file = results.v1;
                    TdApi.Message message = results.v2;
                    TdApi.MessageThreadInfo messageThreadInfo = results.v3;
                    FileRecord dbFileRecord = results.v4;
                    if (file.local != null) {
                        if (file.local.isDownloadingCompleted) {
                            return syncFileDownloadStatus(file, message, messageThreadInfo)
                                    .compose(_ -> DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId));
                        }
                        if (file.local.isDownloadingActive) {
                            return Future.failedFuture("File is downloading");
                        }
//                        return Future.failedFuture("Unknown file download status");
                    }
                    if (dbFileRecord != null && !dbFileRecord.isDownloadStatus(FileRecord.DownloadStatus.idle)
                        && !dbFileRecord.isDownloadStatus(FileRecord.DownloadStatus.error)) {
                        return Future.failedFuture("File is already downloading or completed");
                    }

                    TdApiHelp.FileHandler<? extends TdApi.MessageContent> fileHandler = TdApiHelp.getFileHandler(message)
                            .orElseThrow(() -> VertxException.noStackTrace("not support message type"));
                    FileRecord fileRecord = fileHandler.convertFileRecord(telegramRecord.id()).withThreadInfo(messageThreadInfo);
                    return DataVerticle.fileRepository.createIfNotExist(fileRecord)
                            .compose(created -> {
                                if (!created) {
                                    return DataVerticle.fileRepository.updateFileId(fileRecord.id(), fileRecord.uniqueId());
                                }
                                return Future.succeededFuture();
                            })
                            // Record "downloading" before TDLib starts, so auto-download's slot count (a count of
                            // downloading rows) is right immediately instead of after the first progress update.
                            .compose(ignore -> DataVerticle.fileRepository.updateDownloadStatus(fileId, fileRecord.uniqueId(), null,
                                    FileRecord.DownloadStatus.downloading, null))
                            .compose(ignore -> client.execute(new TdApi.AddFileToDownloads(fileId, chatId, messageId, 32))
                                    .recover(e -> DataVerticle.fileRepository.updateDownloadStatus(fileId, fileRecord.uniqueId(), null,
                                                    FileRecord.DownloadStatus.idle, null)
                                            .transform(_ -> Future.failedFuture(e))))
                            .onSuccess(ignore -> {
                                forgetPaused(fileId);
                                sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                        .put("fileId", fileId)
                                        .put("uniqueId", fileRecord.uniqueId())
                                        .put("downloadStatus", FileRecord.DownloadStatus.downloading)
                                ));

                                downloadThumbnail(chatId, messageId, fileHandler.convertThumbnailRecord(telegramRecord.id()));
                            })
                            .map(fileRecord);
                });
    }

    public Future<Boolean> downloadThumbnail(Long chatId, Long messageId, FileRecord thumbnailRecord) {
        if (thumbnailRecord == null) {
            return Future.succeededFuture(false);
        }
        return DataVerticle.fileRepository.createIfNotExist(thumbnailRecord)
                .compose(created -> {
                    if (!created) {
                        return DataVerticle.fileRepository.updateFileId(thumbnailRecord.id(), thumbnailRecord.uniqueId());
                    }
                    return Future.succeededFuture();
                })
                .compose(ignore -> {
                    if (thumbnailRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)) {
                        return Future.succeededFuture(false);
                    }
                    return client.execute(new TdApi.AddFileToDownloads(thumbnailRecord.id(), chatId, messageId, 32))
                            .map(true);
                })
                .onSuccess(download -> {
                    if (download) {
                        log.debug("[%s] Download thumbnail: %s".formatted(this.getRootId(), thumbnailRecord.uniqueId()));
                    }
                });
    }

    /**
     * Eagerly downloads the lightweight thumbnails for the given messages so previews are crisp
     * while browsing, without having to download the full media. Fire-and-forget: already
     * downloaded thumbnails are skipped by {@link #downloadThumbnail}.
     */
    private static final int MAX_PRELOAD_THUMBNAILS = 30;

    private static final int PRELOAD_THUMBNAIL_CONCURRENCY = 3;

    private void preloadThumbnails(TdApi.FoundChatMessages foundChatMessages) {
        if (foundChatMessages == null || foundChatMessages.messages == null || telegramRecord == null) {
            return;
        }
        // Collect at most MAX_PRELOAD_THUMBNAILS thumbnail records for this page, then download them
        // with bounded concurrency, so opening large pages or fast scrolling can't burst TDLib, the
        // DB and websocket with one download per message.
        List<FileRecord> thumbnails = new ArrayList<>();
        for (TdApi.Message message : foundChatMessages.messages) {
            if (thumbnails.size() >= MAX_PRELOAD_THUMBNAILS) {
                break;
            }
            FileRecord thumbnailRecord = TdApiHelp.getFileHandler(message)
                    .map(fileHandler -> fileHandler.convertThumbnailRecord(telegramRecord.id()))
                    .orElse(null);
            if (thumbnailRecord != null) {
                thumbnails.add(thumbnailRecord);
            }
        }
        if (thumbnails.isEmpty()) {
            return;
        }
        AtomicInteger index = new AtomicInteger(0);
        for (int i = 0; i < Math.min(PRELOAD_THUMBNAIL_CONCURRENCY, thumbnails.size()); i++) {
            preloadNextThumbnail(thumbnails, index);
        }
    }

    private void preloadNextThumbnail(List<FileRecord> thumbnails, AtomicInteger index) {
        int i = index.getAndIncrement();
        if (i >= thumbnails.size()) {
            return;
        }
        FileRecord thumbnailRecord = thumbnails.get(i);
        downloadThumbnail(thumbnailRecord.chatId(), thumbnailRecord.messageId(), thumbnailRecord)
                .onFailure(err -> log.debug("[%s] Preload thumbnail failed for %s: %s"
                        .formatted(getRootId(), thumbnailRecord.uniqueId(), err.getMessage())))
                .onComplete(_ -> preloadNextThumbnail(thumbnails, index));
    }

    public Future<Void> cancelDownload(Integer fileId) {
        return client.execute(new TdApi.GetFile(fileId))
                .compose(file -> DataVerticle.fileRepository
                        .updateFileId(file.id, file.remote.uniqueId)
                        .map(file)
                )
                .compose(file -> {
                    if (file.local == null) {
                        return Future.failedFuture("File not started downloading");
                    }

                    return client.execute(new TdApi.CancelDownloadFile(fileId, false))
                            .map(file);
                })
                .compose(file -> client.execute(new TdApi.DeleteFile(fileId)).map(file))
                .compose(file -> DataVerticle.fileRepository.deleteByUniqueId(file.remote.uniqueId).map(file))
                .onSuccess(file -> forgetPaused(fileId))
                .onSuccess(file ->
                        sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                .put("fileId", fileId)
                                .put("uniqueId", file.remote.uniqueId)
                                .put("downloadStatus", FileRecord.DownloadStatus.idle)
                        )))
                .mapEmpty();
    }

    public Future<Void> togglePauseDownload(Integer fileId, boolean isPaused) {
        return client.execute(new TdApi.GetFile(fileId))
                .compose(file -> DataVerticle.fileRepository
                        .updateFileId(file.id, file.remote.uniqueId)
                        .map(file)
                )
                .compose(file -> {
                    if (file.local == null) {
                        return Future.failedFuture("File not started downloading");
                    }
                    if (file.local.isDownloadingCompleted) {
                        return syncFileDownloadStatus(file, null, null).mapEmpty();
                    }
                    if (isPaused && !file.local.isDownloadingActive) {
                        return Future.failedFuture("File is not downloading");
                    }
                    if (!isPaused && file.local.isDownloadingActive) {
                        return Future.failedFuture("File is downloading");
                    }
                    if (!isPaused && !file.local.canBeDeleted) {
                        // Maybe the file is not exist, so we need to redownload it
                        return DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                                .compose(fileRecord ->
                                        client.execute(new TdApi.AddFileToDownloads(fileId, fileRecord.chatId(), fileRecord.messageId(), 32)))
                                .mapEmpty();
                    }

                    return client.execute(new TdApi.ToggleDownloadIsPaused(fileId, isPaused));
                })
                .onSuccess(_ -> {
                    if (!isPaused) {
                        forgetPaused(fileId);
                    }
                })
                .mapEmpty();
    }

    public Future<Void> removeFile(Integer fileId, String uniqueId) {
        return (fileId == null ? Future.<TdApi.File>succeededFuture() : client.execute(new TdApi.GetFile(fileId)))
                .otherwise((TdApi.File) null)
                .compose(file -> DataVerticle.fileRepository
                        .getByUniqueId(uniqueId)
                        .map(fileRecord -> Tuple.tuple(file, fileRecord))
                )
                .compose(tuple2 -> {
                    TdApi.File file = tuple2.v1;
                    FileRecord fileRecord = tuple2.v2;
                    if (fileRecord == null) {
                        return Future.failedFuture("File not found");
                    }

                    if (fileRecord.isTransferStatus(FileRecord.TransferStatus.completed)) {
                        if (FileUtil.del(fileRecord.localPath())) {
                            log.debug("[%s] Remove file success: %s".formatted(this.getRootId(), fileRecord.localPath()));
                        }
                    }

                    if (file != null && file.local != null && StrUtil.isNotBlank(file.local.path)) {
                        return client.execute(new TdApi.DeleteFile(fileId))
                                .map(file);
                    } else if (!fileRecord.isTransferStatus(FileRecord.TransferStatus.completed)
                               && StrUtil.isNotBlank(fileRecord.localPath())) {
                        if (FileUtil.del(fileRecord.localPath())) {
                            log.debug("[%s] Remove file success: %s".formatted(this.getRootId(), fileRecord.localPath()));
                        }
                    }
                    return Future.succeededFuture(file);
                })
                .compose(file -> DataVerticle.fileRepository.deleteByUniqueId(uniqueId).map(file))
                .onSuccess(_ -> sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                        .put("fileId", fileId)
                        .put("uniqueId", uniqueId)
                        .put("removed", true)
                )))
                .mapEmpty();
    }

    public Future<Void> updateAutoSettings(Long chatId, JsonObject params) {
        return AutomationsHolder.INSTANCE.serialized(() -> DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
                .compose(settingAutoRecords -> {
                    if (settingAutoRecords == null) {
                        settingAutoRecords = new SettingAutoRecords();
                    }
                    SettingAutoRecords.Automation automation = params.mapTo(SettingAutoRecords.Automation.class);
                    boolean hasEnabled = automation.preload.enabled
                                         || automation.download.enabled
                                         || automation.transfer.enabled;

                    if (settingAutoRecords.exists(this.telegramRecord.id(), chatId) && !hasEnabled) {
                        settingAutoRecords.remove(this.telegramRecord.id(), chatId);
                    } else {
                        if (!hasEnabled) {
                            return Future.succeededFuture();
                        }
                        automation.telegramId = this.telegramRecord.id();
                        automation.chatId = chatId;
                        settingAutoRecords.add(automation);
                    }

                    return DataVerticle.settingRepository.createOrUpdate(SettingKey.automation.name(), Json.encode(settingAutoRecords))
                            .onSuccess(r -> vertx.eventBus().publish(EventEnum.AUTO_DOWNLOAD_UPDATE.name(), r.value()));
                }))
                .mapEmpty();
    }

    public Future<JsonObject> getDownloadStatistics() {
        return Future.all(DataVerticle.fileRepository.getDownloadStatistics(this.telegramRecord.id()),
                client.execute(new TdApi.GetNetworkStatistics())
        ).map(r -> {
            JsonObject jsonObject = r.resultAt(0);
            TdApi.NetworkStatistics networkStatistics = r.resultAt(1);
            Tuple2<Long, Long> bytes = Arrays.stream(networkStatistics.entries)
                    .filter(e -> e instanceof TdApi.NetworkStatisticsEntryFile)
                    .map(e -> {
                        TdApi.NetworkStatisticsEntryFile entry = (TdApi.NetworkStatisticsEntryFile) e;
                        return Tuple.tuple(entry.sentBytes, entry.receivedBytes);
                    })
                    .reduce((a, b) -> Tuple.tuple(a.v1 + b.v1, a.v2 + b.v2))
                    .orElse(Tuple.tuple(0L, 0L));

            jsonObject.put("networkStatistics", JsonObject.of()
                    .put("sinceDate", networkStatistics.sinceDate)
                    .put("sentBytes", bytes.v1)
                    .put("receivedBytes", bytes.v2)
            );

            jsonObject.put("speedStats", avgSpeed.getSpeedStats());
            return jsonObject;
        });
    }

    public Future<JsonObject> getDownloadStatisticsByPhase(Integer timeRange) {
        // 1: 1 hour, 2: 1 day, 3: 1 week, 4: 1 month
        long endTime = System.currentTimeMillis();
        long startTime = switch (timeRange) {
            case 1 -> DateUtil.offsetHour(DateUtil.date(), -1).getTime();
            case 2 -> DateUtil.offsetDay(DateUtil.date(), -1).getTime();
            case 3 -> DateUtil.offsetWeek(DateUtil.date(), -1).getTime();
            case 4 -> DateUtil.offsetMonth(DateUtil.date(), -1).getTime();
            default -> throw new IllegalStateException("Unexpected value: " + timeRange);
        };

        return Future.all(
                        DataVerticle.statisticRepository.getRangeStatistics(StatisticRecord.Type.speed, this.telegramRecord.id(), startTime, endTime)
                                .map(statisticRecords -> TelegramConverter.convertRangedSpeedStats(statisticRecords, timeRange)),
                        DataVerticle.fileRepository.getCompletedRangeStatistics(this.telegramRecord.id(), startTime, endTime, timeRange)
                )
                .map(r -> new JsonObject()
                        .put("speedStats", r.resultAt(0))
                        .put("completedStats", r.resultAt(1))
                );
    }

    public Future<TdApi.AddedProxy> enableProxy(String proxyName) {
        if (StrUtil.isBlank(proxyName)) return Future.succeededFuture();
        return DataVerticle.settingRepository.<SettingProxyRecords>getByKey(SettingKey.proxys)
                .map(settingProxyRecords -> Optional.ofNullable(settingProxyRecords)
                        .flatMap(r -> r.getProxy(proxyName))
                        .orElseThrow(() -> VertxException.noStackTrace("Proxy %s not found".formatted(proxyName)))
                )
                .compose(proxy -> this.getTdAddedProxy(proxy)
                        .map(r -> Tuple.tuple(proxy, r))
                )
                .compose(tuple -> {
                    SettingProxyRecords.Item proxy = tuple.v1;
                    TdApi.AddedProxy tdAddedProxy = tuple.v2;
                    boolean edit = false;
                    if (tdAddedProxy != null) {
                        if (tdAddedProxy.isEnabled) {
                            return Future.succeededFuture(tdAddedProxy);
                        }
                        edit = true;
                    }

                    TdApi.ProxyType proxyType;
                    switch (proxy.type) {
                        case "http" -> proxyType = new TdApi.ProxyTypeHttp(proxy.username, proxy.password, false);
                        case "socks5" -> proxyType = new TdApi.ProxyTypeSocks5(proxy.username, proxy.password);
                        case "mtproto" -> proxyType = new TdApi.ProxyTypeMtproto(proxy.secret);
                        case null, default -> {
                            return Future.failedFuture("Unsupported proxy type: %s".formatted(proxy.type));
                        }
                    }
                    TdApi.Proxy tdProxy = new TdApi.Proxy(proxy.server, proxy.port, proxyType);
                    return edit ? client.execute(new TdApi.EditProxy(tdAddedProxy.id, tdProxy, true, proxy.name))
                            : client.execute(new TdApi.AddProxy(tdProxy, true, proxy.name));
                })
                .compose( r -> {
                    this.proxyName = proxyName;
                    if (this.telegramRecord != null) {
                        return DataVerticle.telegramRepository.update(this.telegramRecord.withProxy(proxyName))
                                .onSuccess(telegramRecord -> this.telegramRecord = telegramRecord)
                                .map(r);
                    } else {
                        return Future.succeededFuture(r);
                    }
                });
    }

    public Future<TdApi.AddedProxy> toggleProxy(JsonObject jsonObject) {
        String toggleProxyName = jsonObject.getString("proxyName");
        if (Objects.equals(toggleProxyName, this.proxyName)) {
            return Future.succeededFuture();
        }

        if (StrUtil.isBlank(toggleProxyName) && StrUtil.isNotBlank(this.proxyName)) {
            // disable proxy
            return client.execute(new TdApi.DisableProxy())
                    .compose(_ -> {
                        this.proxyName = null;
                        if (this.telegramRecord != null) {
                            return DataVerticle.telegramRepository.update(this.telegramRecord.withProxy(null))
                                    .onSuccess(telegramRecord -> this.telegramRecord = telegramRecord)
                                    .mapEmpty();
                        }
                        return Future.succeededFuture();
                    });
        } else {
            return this.enableProxy(toggleProxyName);
        }
    }

    public Future<TdApi.AddedProxy> getTdAddedProxy(SettingProxyRecords.Item proxy) {
        return client.execute(new TdApi.GetProxies())
                .map(proxies -> Stream.of(proxies.proxies)
                        .filter(p -> proxy.equalsTdProxy(p.proxy))
                        .findFirst()
                        .orElse(null));
    }

    public Future<TdApi.Proxy> getEnabledTdProxy() {
        return client.execute(new TdApi.GetProxies())
                .map(proxies -> Stream.of(proxies.proxies)
                        .filter(p -> p.isEnabled)
                        .map(p -> p.proxy)
                        .findFirst()
                        .orElse(null));
    }

    public Future<Double> ping() {
        return this.getEnabledTdProxy()
                .compose(proxy -> client.execute(new TdApi.PingProxy(proxy)))
                .map(r -> r.seconds);
    }

    public Future<String> execute(String method, Object params) {
        String code = RandomUtil.randomString(10);
        log.trace("[{}] Execute code: {} method: {}, params: {}", getRootId(), code, method, params);
        return Future.future(promise -> {
            TdApi.Function<?> func = TdApiHelp.getFunction(method, params);
            if (func == null) {
                promise.fail("Unsupported method: " + method);
                return;
            }
            client.getNativeClient().send(func, object -> {
                log.debug("[{}] Execute: [{}] Receive result: {}", getRootId(), code, object);
                handleDefaultResult(object, code);
            });
            promise.complete(code);
        });
    }

    private void sendEvent(EventPayload payload) {
        vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                JsonObject.of("telegramId", this.getId(), "payload", JsonObject.mapFrom(payload)));
    }

    private void sendFileStatusHttpEvent(TdApi.File file, JsonObject fileUpdated) {
        if (fileUpdated == null || fileUpdated.isEmpty()) return;

        JsonObject statusData = new JsonObject()
                .put("fileId", file.id)
                .put("uniqueId", file.remote.uniqueId)
                .put("downloadStatus", fileUpdated.getString("downloadStatus"))
                .put("localPath", fileUpdated.getString("localPath"))
                .put("completionDate", fileUpdated.getLong("completionDate"))
                .put("downloadedSize", file.local.downloadedSize);

        // 如果文件下载完成，尝试获取并包含缩略图文件信息
        if ("completed".equals(fileUpdated.getString("downloadStatus"))) {
            DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                    .compose(mainFileRecord -> {
                        if (mainFileRecord != null) {
                            statusData.put("type", mainFileRecord.type());
                            if (!"thumbnail".equals(mainFileRecord.type())) {
                                vertx.eventBus().publish(EventEnum.FILE_DOWNLOADED.address(), JsonObject.of(
                                        "telegramId", mainFileRecord.telegramId(),
                                        "uniqueId", mainFileRecord.uniqueId()));
                            } else {
                                // Lets the UI patch the rows showing this thumbnail instead of refetching every page.
                                statusData.put("thumbnailFile", JsonObject.of(
                                        "uniqueId", mainFileRecord.uniqueId(),
                                        "mimeType", mainFileRecord.mimeType(),
                                        "extra", StrUtil.isBlank(mainFileRecord.extra()) ? null : Json.decodeValue(mainFileRecord.extra())
                                ));
                            }
                        }
                        if (mainFileRecord != null && mainFileRecord.thumbnailUniqueId() != null) {
                            return FileRecordRetriever.getThumbnails(List.of(mainFileRecord))
                                    .map(thumbnailMap -> {
                                        FileRecord thumbnailRecord = thumbnailMap.get(mainFileRecord.thumbnailUniqueId());
                                        if (thumbnailRecord != null && thumbnailRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)) {
                                            statusData.put("thumbnailFile", JsonObject.of(
                                                    "uniqueId", thumbnailRecord.uniqueId(),
                                                    "mimeType", thumbnailRecord.mimeType(),
                                                    "extra", StrUtil.isBlank(thumbnailRecord.extra()) ? null : Json.decodeValue(thumbnailRecord.extra())
                                            ));
                                        }
                                        return statusData;
                                    });
                        }
                        return Future.succeededFuture(statusData);
                    })
                    .onSuccess(finalStatusData -> sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, finalStatusData)))
                    .onFailure(err -> {
                        // 如果获取缩略图失败，仍然发送基本状态信息
                        log.error("Failed to get thumbnail info for file: %s, error: %s".formatted(file.remote.uniqueId, err.getMessage()));
                        sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, statusData));
                    });
        } else {
            // 非完成状态直接发送
            sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, statusData));
        }
    }

    private void handleAuthorizationResult(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.Error.CONSTRUCTOR:
                sendEvent(EventPayload.build(EventPayload.TYPE_ERROR, object));
                break;
            case TdApi.Ok.CONSTRUCTOR:
                break;
            default:
                log.warn("[%s] Receive UpdateAuthorizationState with invalid authorization state%s".formatted(getRootId(), object));
        }
    }

    private void handleDefaultResult(TdApi.Object object, String code) {
        if (object.getConstructor() == TdApi.Error.CONSTRUCTOR) {
            sendEvent(EventPayload.build(EventPayload.TYPE_ERROR, code, object));
        } else {
            sendEvent(EventPayload.build(EventPayload.TYPE_METHOD_RESULT, code, object));
        }
    }

    private void handleException(Throwable e) {
        log.error(e);
    }

    private Future<Void> cleanupOldVerticle(String oldRootPath) {
        if (oldRootPath.equals(this.rootPath)) {
            return Future.succeededFuture();
        }
        Optional<TelegramVerticle> found = TelegramVerticles.getAll().stream()
                .filter(v -> v != this && oldRootPath.equals(v.rootPath))
                .findFirst();
        if (found.isEmpty()) {
            return Future.succeededFuture();
        }
        TelegramVerticle old = found.get();
        log.info("[%s] Replacing stale verticle at path: %s".formatted(getRootId(), oldRootPath));
        String deployId = old.deploymentID();
        if (deployId == null) {
            // Not deployed; just drop it from the registry.
            TelegramVerticles.remove(old);
            return Future.succeededFuture();
        }
        // Undeploy first; only drop it from the registry once undeploy succeeds. A failed undeploy
        // must not leave a still-running verticle that is no longer tracked/manageable.
        return vertx.undeploy(deployId)
                .onSuccess(_ -> TelegramVerticles.remove(old))
                .recover(e -> {
                    log.warn("[%s] Could not undeploy stale verticle, keeping it registered: %s"
                            .formatted(getRootId(), e.getMessage()));
                    return Future.succeededFuture();
                })
                .mapEmpty();
    }

    private void handleSaveAvgSpeed() {
        if (!authorized || telegramRecord == null) return;
        AvgSpeed.SpeedStats speedStats = avgSpeed.getSpeedStats();
        if (speedStats.avgSpeed() == 0
            && speedStats.minSpeed() == 0
            && speedStats.medianSpeed() == 0
            && speedStats.maxSpeed() == 0) {
            return;
        }
        JsonObject data = JsonObject.mapFrom(speedStats);
        data.remove("interval");
        DataVerticle.statisticRepository.create(new StatisticRecord(Convert.toStr(telegramRecord.id()),
                StatisticRecord.Type.speed,
                System.currentTimeMillis(),
                data.encode()));

        // Avoid speed not being updated for a long time
        avgSpeed.update(0, System.currentTimeMillis());
    }

    private Future<Void> initAvgSpeed() {
        return DataVerticle.settingRepository.<Integer>getByKey(SettingKey.avgSpeedInterval)
                .compose(interval -> {
                    if (Objects.equals(interval, avgSpeed.getSpeedStats().interval())) {
                        if (avgSpeedPersistenceTimerId == 0) {
                            avgSpeedPersistenceTimerId = vertx.setPeriodic(interval * 1000, _ -> handleSaveAvgSpeed());
                        }
                        return Future.succeededFuture();
                    }

                    avgSpeed = new AvgSpeed(interval);
                    if (avgSpeedPersistenceTimerId != 0) {
                        vertx.cancelTimer(avgSpeedPersistenceTimerId);
                    }
                    avgSpeedPersistenceTimerId = vertx.setPeriodic(interval * 1000, _ -> handleSaveAvgSpeed());
                    return Future.succeededFuture();
                });
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.avgSpeedInterval.name()), message -> {
            log.debug("Avg Speed Interval update: %s".formatted(message.body()));
            this.initAvgSpeed();
        });

        return Future.succeededFuture();
    }

    private Future<Void> initDownloadStatusReconciliation() {
        // Set up periodic timer to reconcile download statuses every 30 seconds
        if (downloadStatusReconciliationTimerId == 0) {
            downloadStatusReconciliationTimerId = vertx.setPeriodic(30000, _ -> reconcileDownloadStatuses());
            log.debug("[%s] Download status reconciliation timer initialized".formatted(getRootId()));
        }
        return Future.succeededFuture();
    }

    private void reconcileDownloadStatuses() {
        if (!authorized || telegramRecord == null) {
            return;
        }

        log.trace("[%s] Starting download status reconciliation".formatted(getRootId()));

        DataVerticle.fileRepository.getByDownloadStatus(telegramRecord.id(), FileRecord.DownloadStatus.downloading)
                .onSuccess(fileRecords -> {
                    if (fileRecords == null || fileRecords.isEmpty()) {
                        return;
                    }

                    log.debug("[%s] Reconciling %d files with 'downloading' status".formatted(getRootId(), fileRecords.size()));
                    fileRecords.forEach(fileRecord -> resolveFile(fileRecord)
                            .onSuccess(file -> {
                                if (file.local != null && file.local.isDownloadingCompleted) {
                                    log.info("[%s] Reconciliation: File completed but not updated in DB: %s".formatted(getRootId(), file.remote.uniqueId));
                                    DataVerticle.fileRepository.updateDownloadStatus(
                                            file.id,
                                            file.remote.uniqueId,
                                            file.local.path,
                                            FileRecord.DownloadStatus.completed,
                                            System.currentTimeMillis()
                                    ).onSuccess(result -> sendFileStatusHttpEvent(file, result));
                                } else if (pausedFileIds.contains(file.id)) {
                                    DataVerticle.fileRepository.updateDownloadStatus(file.id, file.remote.uniqueId, null,
                                            FileRecord.DownloadStatus.paused, null);
                                } else if (file.local == null || !file.local.isDownloadingActive) {
                                    // TDLib isn't downloading it (missed update, restart, dropped download): such rows
                                    // used to sit in "downloading" forever, holding auto-download slots. Re-queue it.
                                    log.info("[%s] Reconciliation: resuming stalled download %s".formatted(getRootId(), file.remote.uniqueId));
                                    client.execute(new TdApi.AddFileToDownloads(file.id, fileRecord.chatId(), fileRecord.messageId(), 32))
                                            .onFailure(e -> markDownloadError(fileRecord, e.getMessage()));
                                }
                            })
                            .onFailure(e -> markDownloadError(fileRecord, e.getMessage())));
                })
                .onFailure(e -> log.error("[%s] Failed to get downloading files for reconciliation: %s".formatted(getRootId(), e.getMessage())));
    }

    /**
     * The row's current TDLib file. Stored ids may belong to a previous TDLib session (ids change across
     * restarts), so fall back to the message and refresh the stored id. Fails only if the file is gone.
     */
    private Future<TdApi.File> resolveFile(FileRecord fileRecord) {
        return client.execute(new TdApi.GetFile(fileRecord.id()))
                .otherwise((TdApi.File) null)
                .compose(file -> {
                    if (file != null && file.remote != null && fileRecord.uniqueId().equals(file.remote.uniqueId)) {
                        return Future.succeededFuture(file);
                    }
                    return client.execute(new TdApi.GetMessage(fileRecord.chatId(), fileRecord.messageId()))
                            .compose(message -> TdApiHelp.getFileHandler(message)
                                    .map(TdApiHelp.FileHandler::getFile)
                                    .filter(f -> f.remote != null && fileRecord.uniqueId().equals(f.remote.uniqueId))
                                    .map(f -> DataVerticle.fileRepository.updateFileId(f.id, f.remote.uniqueId).map(f))
                                    .orElseGet(() -> Future.failedFuture("message no longer contains the file")));
                });
    }

    /**
     * Callable from any context; the set is confined to this verticle's.
     */
    private void forgetPaused(int fileId) {
        context.runOnContext(_ -> pausedFileIds.remove(fileId));
    }

    private void markDownloadError(FileRecord fileRecord, String reason) {
        log.warn("[%s] Reconciliation: cannot resume %s, marking error: %s".formatted(getRootId(), fileRecord.uniqueId(), reason));
        DataVerticle.fileRepository.updateDownloadStatus(fileRecord.id(), fileRecord.uniqueId(), null,
                        FileRecord.DownloadStatus.error, null)
                .onSuccess(result -> {
                    if (result != null && !result.isEmpty()) {
                        sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                .put("fileId", fileRecord.id())
                                .put("uniqueId", fileRecord.uniqueId())
                                .put("downloadStatus", FileRecord.DownloadStatus.error)));
                    }
                });
    }

    private void onConnectionStateUpdated(TdApi.ConnectionState connectionState) {
        this.lastConnectionState = connectionState;
        log.debug("[%s] Connection state: %s".formatted(getRootId(), connectionState.getClass().getSimpleName()));
        if (connectionState.getConstructor() == TdApi.ConnectionStateWaitingForNetwork.CONSTRUCTOR) {
            // Tell TDLib the network is available so it retries connecting instead of waiting indefinitely.
            client.execute(new TdApi.SetNetworkType(new TdApi.NetworkTypeOther()), true);
        }
        sendEvent(EventPayload.build(EventPayload.TYPE_CONNECTION, new JsonObject()
                .put("state", connectionStateName(connectionState))));
    }

    private static String connectionStateName(TdApi.ConnectionState state) {
        return switch (state.getConstructor()) {
            case TdApi.ConnectionStateReady.CONSTRUCTOR -> "ready";
            case TdApi.ConnectionStateConnecting.CONSTRUCTOR -> "connecting";
            case TdApi.ConnectionStateConnectingToProxy.CONSTRUCTOR -> "connectingToProxy";
            case TdApi.ConnectionStateUpdating.CONSTRUCTOR -> "updating";
            case TdApi.ConnectionStateWaitingForNetwork.CONSTRUCTOR -> "waitingForNetwork";
            default -> "unknown";
        };
    }

    private void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
        log.debug("[{}] Receive authorization state update: {}", getRootId(), authorizationState);
        this.lastAuthorizationState = authorizationState;
        switch (authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                TdApi.SetTdlibParameters request = new TdApi.SetTdlibParameters();
                request.databaseDirectory = this.rootPath;
                request.useMessageDatabase = true;
                request.useFileDatabase = true;
                request.useChatInfoDatabase = true;
                request.useSecretChats = true;
                request.apiId = Config.TELEGRAM_API_ID;
                request.apiHash = Config.TELEGRAM_API_HASH;
                request.systemLanguageCode = "en";
                request.deviceModel = "Telegram Files";
                request.applicationVersion = Start.VERSION;
                log.trace("[{}] Send SetTdlibParameters: {}", getRootId(), request);

                client.execute(request).onSuccess(this::handleAuthorizationResult);
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitEmailAddress.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitEmailCode.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitPremiumPurchase.CONSTRUCTOR:
                authorized = false;
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                break;
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                authorized = true;
                if (telegramRecord == null) {
                    client.execute(new TdApi.GetMe())
                            .compose(user -> {
                                TelegramRecord record = new TelegramRecord(user.id, user.firstName, this.rootPath, this.proxyName);
                                // Check existence explicitly rather than inferring a duplicate-key from the
                                // exception message (brittle across DB drivers/versions/locales).
                                return DataVerticle.telegramRepository.getById(user.id)
                                        .compose(existing -> {
                                            if (existing == null) {
                                                return DataVerticle.telegramRepository.create(record);
                                            }
                                            // Account already registered (e.g. re-auth into a fresh root):
                                            // take over by cleaning up the stale verticle, then update the record.
                                            return cleanupOldVerticle(existing.rootPath())
                                                    .compose(_ -> DataVerticle.telegramRepository.update(record));
                                        });
                            })
                            .onSuccess(o -> {
                                telegramRecord = o;
                                log.info("[%s] %s Authorization Ready".formatted(getRootId(), this.telegramRecord.firstName()));
                            })
                            .onFailure(e -> log.error("[%s] Authorization Ready, but failed to create telegram record: %s".formatted(getRootId(), e.getMessage())));
                } else {
                    log.info("[%s] %s Authorization Ready".formatted(getRootId(), this.telegramRecord.firstName()));
                }
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                telegramChats.loadMainChatList();
                telegramChats.loadArchivedChatList();
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                authorized = false;
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                authorized = false;
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                authorized = false;
                if (needDelete) {
                    File root = FileUtil.file(this.rootPath);
                    vertx.executeBlocking(() -> root.exists() && FileUtil.del(root))
                            .onFailure(e -> log.error("[%s] Failed to delete account files: %s".formatted(this.getRootId(), e.getMessage())));
                    if (getId() instanceof Long telegramId) {
                        DataVerticle.telegramRepository.delete(telegramId)
                                .onFailure(e -> log.error("[%s] Failed to delete telegram record: %s".formatted(this.getRootId(), e.getMessage())));
                    }
                    log.info("[%s] Telegram account deleted".formatted(this.getRootId()));
                }
                break;
            default:
                log.warn("[%s] Unsupported authorization state received:%s".formatted(this.getRootId(), authorizationState));
        }
    }

    private void onFileUpdated(TdApi.UpdateFile updateFile) {
        TdApi.File file = updateFile.file;
        if (file == null) {
            return;
        }
        if (log.isTraceEnabled()) {
            log.trace("📃[{}] Receive file update: {}", getRootId(), updateFile);
        }
        boolean completed = file.local != null && file.local.isDownloadingCompleted;
        FileRecord.DownloadStatus downloadStatus = Objects.requireNonNullElse(TdApiHelp.getDownloadStatus(file),
                completed ? FileRecord.DownloadStatus.completed : FileRecord.DownloadStatus.idle);
        if (completed) {
            pausedFileIds.remove(file.id);
        } else if (downloadStatus == FileRecord.DownloadStatus.idle && pausedFileIds.contains(file.id)) {
            // TDLib reports a paused download as merely inactive.
            downloadStatus = FileRecord.DownloadStatus.paused;
        }

        // A download fires many updates per second; once the database says "downloading" there is
        // nothing to persist until the status changes.
        if (downloadStatus != FileRecord.DownloadStatus.downloading) {
            downloadingFileIds.remove(file.id);
        }
        if (downloadStatus != FileRecord.DownloadStatus.downloading || !downloadingFileIds.contains(file.id)) {
            persistFileStatus(file, downloadStatus, completed);
        }

        // Throttle per file: with one shared timestamp, concurrent downloads starved each other's progress.
        long now = System.currentTimeMillis();
        Long lastSent = lastFileEventTimes.get(file.id);
        if (downloadStatus != FileRecord.DownloadStatus.downloading || lastSent == null || now - lastSent > 1000) {
            sendEvent(EventPayload.build(EventPayload.TYPE_FILE, updateFile));
            if (downloadStatus == FileRecord.DownloadStatus.downloading) {
                lastFileEventTimes.put(file.id, now);
            } else {
                lastFileEventTimes.remove(file.id);
            }
        }
    }

    private void persistFileStatus(TdApi.File file, FileRecord.DownloadStatus downloadStatus, boolean completed) {
        DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                .onSuccess(fileRecord -> {
                    if (fileRecord == null) {
                        return;
                    }
                    if (fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed) &&
                        fileRecord.isTransferStatus(FileRecord.TransferStatus.completed) &&
                        FileUtil.exist(fileRecord.localPath())) {
                        return;
                    }
                    // Decided now, not when the update arrived: a pause may have been reported meanwhile.
                    FileRecord.DownloadStatus status = downloadStatus;
                    boolean paused = pausedFileIds.contains(file.id);
                    if (!completed && paused) {
                        status = FileRecord.DownloadStatus.paused;
                    } else if (status == FileRecord.DownloadStatus.idle && fileRecord.isDownloadStatus(FileRecord.DownloadStatus.paused)) {
                        return;
                    }
                    if (fileRecord.id() != file.id) {
                        // TDLib file ids change between sessions; keep the stored one current.
                        DataVerticle.fileRepository.updateFileId(file.id, file.remote.uniqueId);
                    }
                    if (status == FileRecord.DownloadStatus.downloading) {
                        downloadingFileIds.add(file.id);
                    }
                    DataVerticle.fileRepository.updateDownloadStatus(file.id,
                                    file.remote.uniqueId,
                                    completed ? file.local.path : null,
                                    status,
                                    completed ? System.currentTimeMillis() : null)
                            .onSuccess(r -> sendFileStatusHttpEvent(file, r));
                });
    }

    private void onFileDownloadUpdated(TdApi.UpdateFileDownload update) {
        if (!update.isPaused || update.completeDate != 0) {
            pausedFileIds.remove(update.fileId);
            return;
        }
        if (!pausedFileIds.add(update.fileId)) {
            return;
        }
        downloadingFileIds.remove(update.fileId);
        // Without this, paused files were stored as "idle": the paused counter stayed at 0 and
        // auto-download picked them up again, silently resuming what the user had paused.
        client.execute(new TdApi.GetFile(update.fileId))
                .compose(file -> file.local != null && file.local.isDownloadingCompleted
                        ? Future.succeededFuture()
                        : DataVerticle.fileRepository.updateDownloadStatus(file.id, file.remote.uniqueId, null,
                                        FileRecord.DownloadStatus.paused, null)
                                .onSuccess(r -> sendFileStatusHttpEvent(file, r)))
                .onFailure(e -> log.debug("[%s] Failed to record paused file %d: %s".formatted(getRootId(), update.fileId, e.getMessage())));
    }

    private void onFileDownloadsUpdated(TdApi.UpdateFileDownloads updateFileDownloads) {
        if (log.isTraceEnabled()) {
            log.trace("[{}] Receive file downloads update: {}", getRootId(), updateFileDownloads);
        }
        avgSpeed.update(updateFileDownloads.downloadedSize, System.currentTimeMillis());
        if (lastFileDownloadEventTime == 0 || System.currentTimeMillis() - lastFileDownloadEventTime > 1000) {
            sendEvent(EventPayload.build(EventPayload.TYPE_FILE_DOWNLOAD, updateFileDownloads));
            lastFileDownloadEventTime = System.currentTimeMillis();
        }
    }

    private void onMessageReceived(TdApi.Message message) {
        if (log.isTraceEnabled()) {
            log.trace("[{}] Receive message: {}", getRootId(), message);
        }
        if (this.telegramRecord == null) {
            return;
        }
        vertx.eventBus().publish(EventEnum.MESSAGE_RECEIVED.address(), JsonObject.of()
                .put("telegramId", telegramRecord.id())
                .put("chatId", message.chatId)
                .put("messageId", message.id)
        );
    }

    private Future<Void> syncFileDownloadStatus(TdApi.File file, TdApi.Message message, TdApi.MessageThreadInfo messageThreadInfo) {
        return DataVerticle.fileRepository
                .getByUniqueId(file.remote.uniqueId)
                .compose(fileRecord -> {
                    if (fileRecord != null) {
                        return DataVerticle.fileRepository.updateDownloadStatus(
                                file.id,
                                file.remote.uniqueId,
                                file.local.path,
                                FileRecord.DownloadStatus.completed,
                                System.currentTimeMillis()
                        );
                    }

                    if (message == null) {
                        return Future.failedFuture("File not found");
                    }

                    fileRecord = TdApiHelp.getFileHandler(message)
                            .orElseThrow(() -> VertxException.noStackTrace("not support message type"))
                            .convertFileRecord(telegramRecord.id())
                            .withThreadInfo(messageThreadInfo);

                    return DataVerticle.fileRepository.create(fileRecord)
                            .compose(_ -> DataVerticle.fileRepository.updateDownloadStatus(
                                    file.id,
                                    file.remote.uniqueId,
                                    file.local.path,
                                    FileRecord.DownloadStatus.completed,
                                    System.currentTimeMillis()
                            ));
                })
                .compose(r -> {
                    sendFileStatusHttpEvent(file, r);
                    // Idempotent: an empty update means the row already holds the completed state.
                    return Future.succeededFuture();
                });
    }
}
