package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AutomationsHolder {
    private final Log log = LogFactory.get();

    private final SettingAutoRecords AUTO_RECORDS = new SettingAutoRecords();

    private final List<Consumer<List<SettingAutoRecords.Automation>>> onRemoveListeners = new ArrayList<>();

    private volatile boolean initialized = false;

    private Future<?> writes = Future.succeededFuture();

    public static final AutomationsHolder INSTANCE = new AutomationsHolder();

    private AutomationsHolder() {
    }

    public SettingAutoRecords autoRecords() {
        return AUTO_RECORDS;
    }

    public void registerOnRemoveListener(Consumer<List<SettingAutoRecords.Automation>> onRemove) {
        onRemoveListeners.add(onRemove);
    }

    public synchronized Future<Void> init() {
        if (initialized) {
            return Future.succeededFuture();
        }
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
                .onSuccess(settingAutoRecords -> {
                    initialized = true;
                    if (settingAutoRecords == null) {
                        return;
                    }
                    settingAutoRecords.automations.forEach(item -> TelegramVerticles.get(item.telegramId)
                            .ifPresentOrElse(_ -> AUTO_RECORDS.add(item),
                                    () -> log.warn("Init auto records fail. Telegram verticle not found: %s".formatted(item.telegramId))));
                })
                .onFailure(e -> log.error("Init auto records failed!", e))
                .mapEmpty();
    }

    public void onAutoRecordsUpdate(SettingAutoRecords records) {
        for (SettingAutoRecords.Automation automation : records.automations) {
            if (!AUTO_RECORDS.exists(automation.telegramId, automation.chatId)) {
                // new enabled
                TelegramVerticles.get(automation.telegramId)
                        .ifPresentOrElse(telegramVerticle -> {
                            if (telegramVerticle.authorized) {
                                AUTO_RECORDS.add(automation);
                                log.info("Add auto records success: %s".formatted(automation.uniqueKey()));
                            } else {
                                log.warn("Add auto records fail. Telegram verticle not authorized: %s".formatted(automation.telegramId));
                            }
                        }, () -> log.warn("Add auto records fail. Telegram verticle not found: %s".formatted(automation.telegramId)));
            } else {
                // update fields
                SettingAutoRecords.Automation theAutomation = AUTO_RECORDS.getItem(automation.telegramId, automation.chatId);
                theAutomation.preload.with(automation.preload);
                theAutomation.download.with(automation.download);
                theAutomation.transfer.with(automation.transfer);
                log.info("Update auto records success: %s".formatted(automation.uniqueKey()));
            }
        }
        // remove disabled
        List<SettingAutoRecords.Automation> removedItems = new ArrayList<>();
        AUTO_RECORDS.automations.removeIf(item -> {
            if (records.exists(item.telegramId, item.chatId)) {
                return false;
            }
            removedItems.add(item);
            log.info("Remove auto records success: %s".formatted(item.uniqueKey()));
            return true;
        });
        if (CollUtil.isNotEmpty(removedItems)) {
            onRemoveListeners.forEach(listener -> listener.accept(removedItems));
        }
    }

    /**
     * Runs read-modify-write operations on the automation setting one at a time: the periodic progress save
     * and a user's edit used to interleave and drop (or resurrect) each other's changes.
     */
    public synchronized <T> Future<T> serialized(Supplier<Future<T>> operation) {
        Future<T> next = writes.transform(_ -> operation.get());
        writes = next.transform(_ -> Future.succeededFuture());
        return next;
    }

    /**
     * Persists scan progress (cursors, completion state) onto the stored automations. Never adds entries:
     * the user's settings are the source of truth for which automations exist and how they're configured.
     */
    public Future<Void> saveAutoRecords() {
        return serialized(() -> DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
                .compose(stored -> {
                    if (stored == null) {
                        return Future.succeededFuture();
                    }
                    for (SettingAutoRecords.Automation live : AUTO_RECORDS.automations) {
                        SettingAutoRecords.Automation saved = stored.getItem(live.telegramId, live.chatId);
                        if (saved == null) {
                            continue;
                        }
                        saved.state = live.state;
                        if (saved.preload != null && live.preload != null) {
                            saved.preload.nextFromMessageId = live.preload.nextFromMessageId;
                        }
                        if (saved.download != null && live.download != null) {
                            saved.download.nextFileType = live.download.nextFileType;
                            saved.download.nextFromMessageId = live.download.nextFromMessageId;
                        }
                    }
                    return DataVerticle.settingRepository.createOrUpdate(SettingKey.automation.name(), Json.encode(stored));
                }))
                .onFailure(e -> log.error("Save auto records failed!", e))
                .mapEmpty();
    }
}
