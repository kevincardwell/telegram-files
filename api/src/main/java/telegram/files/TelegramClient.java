package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

public class TelegramClient {
    private static final Log log = LogFactory.get();

    private Client client;

    private boolean initialized = false;

    static {
        Client.setLogMessageHandler(0, new LogMessageHandler());

        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(Config.TELEGRAM_LOG_LEVEL));
            Client.execute(new TdApi.SetLogStream(new TdApi.LogStreamFile(Path.of(Config.LOG_PATH, "tdlib.log").toString(),
                    1 << 27, false)));
        } catch (Client.ExecutionException error) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }
    }

    public void initialize(Client.ResultHandler updateHandler,
                           Client.ExceptionHandler updateExceptionHandler,
                           Client.ExceptionHandler defaultExceptionHandler) {
        synchronized (this) {
            if (!initialized) {
                client = Client.create(updateHandler, updateExceptionHandler, defaultExceptionHandler);
                initialized = true;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method) {
        return execute(method, false);
    }

    @SuppressWarnings("unchecked")
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method, boolean ignoreException) {
        if (!initialized) {
            throw new IllegalStateException("Client is not initialized");
        }
        // TDLib answers on its single native thread. Complete on the caller's Vert.x context instead, so
        // continuations neither race each other on shared state nor stall every account's updates.
        Context context = Vertx.currentContext();
        Promise<R> promise = Promise.promise();
        client.send(method, object -> {
            Runnable complete = () -> {
                if (object.getConstructor() == TdApi.Error.CONSTRUCTOR) {
                    if (ignoreException) {
                        promise.complete(null);
                    } else {
                        promise.fail(new TelegramRunException((TdApi.Error) object));
                    }
                } else {
                    promise.complete((R) object);
                }
            };
            if (context == null) {
                complete.run();
            } else {
                context.runOnContext(_ -> complete.run());
            }
        });
        return promise.future();
    }

    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method, long timeoutMs, Vertx vertx) {
        Promise<R> promise = Promise.promise();

        long timerId = vertx.setTimer(timeoutMs, _ -> {
            if (!promise.future().isComplete()) {
                promise.fail(new TimeoutException("Operation timed out after " + timeoutMs + " ms"));
            }
        });

        execute(method).onComplete(ar -> {
            vertx.cancelTimer(timerId);
            if (promise.future().isComplete()) {
                return;
            }
            if (ar.succeeded()) {
                promise.complete(ar.result());
            } else {
                promise.fail(ar.cause());
            }
        });

        return promise.future();
    }

    public Client getNativeClient() {
        return client;
    }

    private static class LogMessageHandler implements Client.LogMessageHandler {
        @Override
        public void onLogMessage(int verbosityLevel, String message) {
            log.debug("TDLib: {}", message);
        }
    }
}
