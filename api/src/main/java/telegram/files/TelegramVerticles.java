package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxException;
import telegram.files.repository.TelegramRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public class TelegramVerticles {
    private static final Log log = LogFactory.get();

    private static final List<TelegramVerticle> telegramVerticles = new CopyOnWriteArrayList<>();

    public static Future<Void> initTelegramVerticles(Vertx vertx) {
        return DataVerticle.telegramRepository.getAll()
                .compose(telegramRecords -> {
                    List<String> verifiedPath = telegramRecords.stream().map(TelegramRecord::rootPath).toList();
                    File telegramRoot = FileUtil.file(Config.TELEGRAM_ROOT);
                    List<String> uncertifiedPaths = FileUtil.loopFiles(telegramRoot, 1, null)
                            .stream()
                            .filter(f -> f.isDirectory() && !verifiedPath.contains(f.getAbsolutePath()))
                            .map(File::getAbsolutePath)
                            .toList();
                    List<Future<String>> futures = new ArrayList<>();
                    for (TelegramRecord telegramRecord : telegramRecords) {
                        TelegramVerticle telegramVerticle = new TelegramVerticle(telegramRecord);
                        if (!telegramVerticle.check()) {
                            continue;
                        }
                        telegramVerticles.add(telegramVerticle);
                        futures.add(vertx.deployVerticle(telegramVerticle));
                    }
                    if (CollUtil.isNotEmpty(uncertifiedPaths)) {
                        for (String uncertifiedPath : uncertifiedPaths) {
                            TelegramVerticle telegramVerticle = new TelegramVerticle(uncertifiedPath);
                            if (!telegramVerticle.check()) {
                                continue;
                            }
                            telegramVerticles.add(telegramVerticle);
                            futures.add(vertx.deployVerticle(telegramVerticle));
                        }
                    }
                    return Future.all(futures);
                })
                .onSuccess(r -> log.info("Successfully deployed %d telegram verticles".formatted(r.size())))
                .onFailure(err -> log.error("Failed to deploy telegram verticles: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    public static void add(TelegramVerticle telegramVerticle) {
        telegramVerticles.add(telegramVerticle);
    }

    public static void remove(TelegramVerticle telegramVerticle) {
        telegramVerticles.remove(telegramVerticle);
    }

    public static List<TelegramVerticle> getAll() {
        return telegramVerticles;
    }

    public static Optional<TelegramVerticle> get(String telegramId) {
        Object id = NumberUtil.isNumber(telegramId) ? Convert.toLong(telegramId) : telegramId;
        return telegramVerticles.stream()
                .filter(t -> Objects.equals(t.getId(), id))
                .findFirst();
    }

    public static TelegramVerticle getOrElseThrow(String telegramId) {
        return get(telegramId)
                .orElseThrow(() -> VertxException.noStackTrace("Telegram account not found!"));
    }

    public static Optional<TelegramVerticle> get(long telegramId) {
        return telegramVerticles.stream()
                .filter(t -> t.telegramRecord != null && t.telegramRecord.id() == telegramId)
                .findFirst();
    }

    public static TelegramVerticle getOrElseThrow(long telegramId) {
        return get(telegramId)
                .orElseThrow(() -> VertxException.noStackTrace("Telegram account not found!"));
    }
}
