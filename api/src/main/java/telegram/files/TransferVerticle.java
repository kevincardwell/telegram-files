package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.jooq.lambda.tuple.Tuple3;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class TransferVerticle extends AbstractVerticle {
    private static final Log log = LogFactory.get();

    private static final int HISTORY_SCAN_INTERVAL = 2 * 60 * 1000;

    private static final int TRANSFER_INTERVAL = 3 * 1000;

    private static final int HISTORY_BATCH_SIZE = 200;

    private final SettingAutoRecords autoRecords;

    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();

    private final BlockingQueue<WaitingTransferFile> waitingTransferFiles = new LinkedBlockingQueue<>();

    private volatile boolean isStopped = false;

    private volatile Transfer beingTransferred;

    private boolean draining = false;

    public TransferVerticle() {
        this.autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        AutomationsHolder.INSTANCE.registerOnRemoveListener(removedItems -> removedItems.forEach(item -> {
            waitingTransferFiles.removeIf(w -> w.telegramId() == item.telegramId && w.chatId() == item.chatId);
            transfers.remove(item.uniqueKey());
        }));
    }

    @Override
    public void start(Promise<Void> startPromise) {
        // A crash mid-move left rows "transferring" forever: only idle files are ever picked up again.
        SqlTemplate.forUpdate(DataVerticle.pool, "UPDATE file_record SET transfer_status = 'idle' WHERE transfer_status = 'transferring'")
                .execute(Map.of())
                .onSuccess(r -> {
                    if (r.rowCount() > 0) log.info("Reset %d interrupted transfers".formatted(r.rowCount()));
                })
                .<Void>mapEmpty()
                .recover(e -> {
                    log.error("Failed to reset interrupted transfers: %s".formatted(e.getMessage()));
                    return Future.succeededFuture();
                })
                .compose(_ -> initEventConsumer()).onSuccess(_ -> {
            vertx.setPeriodic(0, HISTORY_SCAN_INTERVAL, _ -> addHistoryFiles());
            vertx.setPeriodic(0, TRANSFER_INTERVAL, _ -> startTransfer());

            log.info("""
                    Transfer verticle started!
                    |History scan interval: %s ms
                    |Transfer interval: %s ms
                    |Auto chats: %s
                    """.formatted(HISTORY_SCAN_INTERVAL, TRANSFER_INTERVAL, autoRecords.getTransferEnabledItems().size()));

            startPromise.complete();
        }).onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        isStopped = true;
        if (beingTransferred != null) {
            log.info("Wait for transfer to complete, file: %s".formatted(beingTransferred.getTransferRecord().uniqueId()));
            while (beingTransferred != null) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.error("Stop transfer verticle error: %s".formatted(e.getMessage()));
                    stopPromise.fail(e);
                }
            }
        }
        log.info("Transfer verticle stopped");
        stopPromise.complete();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.FILE_DOWNLOADED.address(), message -> {
            String uniqueId = ((JsonObject) message.body()).getString("uniqueId");
            FileRecord fileRecord = Future.await(DataVerticle.fileRepository.getByUniqueId(uniqueId));
            if (fileRecord == null || "thumbnail".equals(fileRecord.type())) {
                // Thumbnails are internal preview files; never transfer them.
                return;
            }

            SettingAutoRecords.Automation automation = null;
            if (fileRecord.threadChatId() != 0 && fileRecord.messageThreadId() != 0 && fileRecord.threadChatId() == fileRecord.chatId()) {
                // thread message file,try to get the main message
                FileRecord mainFileRecord = Future.await(DataVerticle.fileRepository.getMainFileByThread(
                        fileRecord.telegramId(),
                        fileRecord.threadChatId(),
                        fileRecord.messageThreadId()));
                if (mainFileRecord != null) {
                    automation = autoRecords.getItem(mainFileRecord.telegramId(), mainFileRecord.chatId());
                }
            } else {
                automation = autoRecords.getItem(fileRecord.telegramId(), fileRecord.chatId());
            }

            if (automation == null || !automation.transfer.enabled || getTransfer(automation) == null) {
                return;
            }

            if (addWaitingTransferFile(automation.telegramId, automation.chatId, fileRecord.uniqueId())) {
                log.debug("Add file to transfer queue: %s".formatted(fileRecord.uniqueId()));
            }
        });

        return Future.succeededFuture();
    }

    private void addHistoryFiles() {
        if (CollUtil.isEmpty(autoRecords.automations)) {
            return;
        }
        log.trace("Start scan history files for transfer");
        for (SettingAutoRecords.Automation automation : autoRecords.automations) {
            if (!automation.transfer.enabled
                || !automation.transfer.rule.transferHistory
                || automation.isComplete(SettingAutoRecords.HISTORY_TRANSFER_STATE)) {
                continue;
            }
            Transfer transfer = getTransfer(automation);
            if (transfer == null) {
                continue;
            }
            Tuple3<List<FileRecord>, Long, Long> filesTuple = Future.await(DataVerticle.fileRepository.getFiles(automation.chatId,
                    Map.of("downloadStatus", FileRecord.DownloadStatus.completed.name(),
                            "transferStatus", FileRecord.TransferStatus.idle.name(),
                            "limit", String.valueOf(HISTORY_BATCH_SIZE)
                    )
            ));
            List<FileRecord> files = filesTuple.v1;
            if (CollUtil.isEmpty(files)) {
                log.debug("No history files found for transfer: %s".formatted(automation.uniqueKey()));
                automation.complete(SettingAutoRecords.HISTORY_TRANSFER_STATE);
                continue;
            }

            int count = 0;
            for (FileRecord fileRecord : files) {
                if ("thumbnail".equals(fileRecord.type())) {
                    // Thumbnails are internal preview files; never transfer them.
                    continue;
                }
                if (addWaitingTransferFile(fileRecord)) {
                    count++;
                }
            }

            if (count > 0) {
                log.info("Add history files to transfer queue: %s".formatted(count));
            }
        }
    }

    private boolean addWaitingTransferFile(FileRecord fileRecord) {
        return addWaitingTransferFile(fileRecord.telegramId(), fileRecord.chatId(), fileRecord.uniqueId());
    }

    private boolean addWaitingTransferFile(long telegramId, long chatId, String uniqueId) {
        WaitingTransferFile waitingTransferFile = new WaitingTransferFile(telegramId, chatId, uniqueId);
        if (!waitingTransferFiles.contains(waitingTransferFile)) {
            waitingTransferFiles.add(waitingTransferFile);
            return true;
        }
        return false;
    }

    private Transfer getTransfer(SettingAutoRecords.Automation automation) {
        if (automation == null || !automation.transfer.enabled) {
            return null;
        }

        SettingAutoRecords.TransferRule transferRule = automation.transfer.rule;

        if (transfers.containsKey(automation.uniqueKey())) {
            Transfer transfer = transfers.get(automation.uniqueKey());
            if (!transfer.isRuleUpdated(transferRule)) {
                return transfer;
            } else {
                log.debug("Transfer rule updated: %s".formatted(automation.uniqueKey()));
                transfers.remove(automation.uniqueKey());
            }
        }

        return transfers.computeIfAbsent(automation.uniqueKey(), _ -> {
            Transfer transfer = Transfer.create(transferRule);
            transfer.transferStatusUpdated = updated ->
                    updateTransferStatus(updated.fileRecord(), updated.transferStatus(), updated.localPath());
            return transfer;
        });
    }

    /**
     * Drains the queue (it used to move one file per tick, i.e. at most one file every 3 seconds).
     * When a drain moved files, the next history batch is queued right away instead of in 2 minutes.
     */
    public void startTransfer() {
        if (beingTransferred != null || draining) {
            return;
        }
        draining = true;
        int moved = 0;
        try {
            WaitingTransferFile waitingTransferFile;
            while (!isStopped && (waitingTransferFile = waitingTransferFiles.poll()) != null) {
                Transfer transfer = transfers.get("%d:%d".formatted(waitingTransferFile.telegramId(), waitingTransferFile.chatId()));
                if (transfer == null) {
                    continue;
                }
                FileRecord fileRecord = Future.await(DataVerticle.fileRepository.getByUniqueId(waitingTransferFile.uniqueId));
                if (fileRecord == null) {
                    log.error("File not found: %s".formatted(waitingTransferFile.uniqueId));
                    continue;
                }
                if (startTransfer(fileRecord, transfer)) {
                    moved++;
                }
            }
        } catch (Exception e) {
            log.error(e, "Transfer error");
        } finally {
            draining = false;
        }
        if (moved > 0 && !isStopped) {
            addHistoryFiles();
        }
    }

    /**
     * @return whether the file left the idle state (so it won't be picked up by the history scan again)
     */
    public boolean startTransfer(FileRecord fileRecord, Transfer transfer) {
        if (isStopped) {
            return false;
        }
        if (fileRecord.transferStatus() != null
            && !fileRecord.isTransferStatus(FileRecord.TransferStatus.idle)) {
            log.debug("File {} transfer status is not idle: {}", fileRecord.id(), fileRecord.transferStatus());
            return false;
        }
        if (!fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)
            || StrUtil.isBlank(fileRecord.localPath())) {
            // Mark it instead of skipping silently: it would otherwise be re-queued forever.
            log.warn("File {} has no downloaded copy to transfer", fileRecord.uniqueId());
            updateTransferStatus(fileRecord, FileRecord.TransferStatus.error, null);
            return true;
        }

        beingTransferred = transfer;
        try {
            transfer.transfer(fileRecord);
        } finally {
            beingTransferred = null;
        }
        return true;
    }

    private void updateTransferStatus(FileRecord fileRecord, FileRecord.TransferStatus transferStatus, String localPath) {
        Future.await(DataVerticle.fileRepository.updateTransferStatus(fileRecord.uniqueId(), transferStatus, localPath)
                .onSuccess(fileUpdated -> {
                    if (fileUpdated != null && !fileUpdated.isEmpty()) {
                        EventPayload payload = EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                .put("fileId", fileRecord.id())
                                .put("uniqueId", fileRecord.uniqueId())
                                .put("transferStatus", fileUpdated.getString("transferStatus"))
                                .put("localPath", fileUpdated.getString("localPath"))
                        );
                        vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                                JsonObject.of("telegramId", fileRecord.telegramId(), "payload", JsonObject.mapFrom(payload))
                        );
                    }
                }));
    }

    private record WaitingTransferFile(long telegramId, long chatId, String uniqueId) {
    }
}
