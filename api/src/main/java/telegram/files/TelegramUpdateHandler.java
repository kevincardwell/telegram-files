package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.function.Consumer;

public class TelegramUpdateHandler implements Client.ResultHandler {

    private static final Log log = LogFactory.get();

    private Consumer<TdApi.AuthorizationState> onAuthorizationStateUpdated;

    private Consumer<TdApi.UpdateFile> onFileUpdated;

    private Consumer<TdApi.UpdateFileDownload> onFileDownloadUpdated;

    private Consumer<TdApi.UpdateFileDownloads> onFileDownloadsUpdated;

    private Consumer<TdApi.Object> onChatUpdated;

    private Consumer<TdApi.Message> onMessageReceived;

    private Consumer<TdApi.ConnectionState> onConnectionStateUpdated;

    @Override
    public void onResult(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.UpdateAuthorizationState.CONSTRUCTOR:
                if (onAuthorizationStateUpdated != null)
                    onAuthorizationStateUpdated.accept(((TdApi.UpdateAuthorizationState) object).authorizationState);
                break;
            case TdApi.UpdateConnectionState.CONSTRUCTOR:
                if (onConnectionStateUpdated != null)
                    onConnectionStateUpdated.accept(((TdApi.UpdateConnectionState) object).state);
                break;
            case TdApi.UpdateFile.CONSTRUCTOR:
                if (onFileUpdated != null)
                    onFileUpdated.accept((TdApi.UpdateFile) object);
                break;
            case TdApi.UpdateFileDownload.CONSTRUCTOR:
                if (onFileDownloadUpdated != null)
                    onFileDownloadUpdated.accept((TdApi.UpdateFileDownload) object);
                break;
            case TdApi.UpdateFileDownloads.CONSTRUCTOR:
                if (onFileDownloadsUpdated != null)
                    onFileDownloadsUpdated.accept((TdApi.UpdateFileDownloads) object);
                break;
            case TdApi.UpdateNewMessage.CONSTRUCTOR:
                if (onMessageReceived != null) {
                    onMessageReceived.accept(((TdApi.UpdateNewMessage) object).message);
                }
                break;
            case TdApi.UpdateNewChat.CONSTRUCTOR:
            case TdApi.UpdateChatTitle.CONSTRUCTOR:
            case TdApi.UpdateChatPhoto.CONSTRUCTOR:
            case TdApi.UpdateChatReadInbox.CONSTRUCTOR:
            case TdApi.UpdateChatLastMessage.CONSTRUCTOR:
            case TdApi.UpdateChatPosition.CONSTRUCTOR:
                if (onChatUpdated != null) {
                    onChatUpdated.accept(object);
                }
                break;
            default:
                // Lazy: TdApi.toString() is a native call and this branch sees most of TDLib's update stream.
                if (log.isTraceEnabled()) {
                    log.trace("Unsupported telegram update: {}", object);
                }
        }
    }

    public void setOnAuthorizationStateUpdated(Consumer<TdApi.AuthorizationState> onAuthorizationStateUpdated) {
        this.onAuthorizationStateUpdated = onAuthorizationStateUpdated;
    }

    public void setOnFileUpdated(Consumer<TdApi.UpdateFile> onFileUpdated) {
        this.onFileUpdated = onFileUpdated;
    }

    public void setOnFileDownloadUpdated(Consumer<TdApi.UpdateFileDownload> onFileDownloadUpdated) {
        this.onFileDownloadUpdated = onFileDownloadUpdated;
    }

    public void setOnFileDownloadsUpdated(Consumer<TdApi.UpdateFileDownloads> onFileDownloadsUpdated) {
        this.onFileDownloadsUpdated = onFileDownloadsUpdated;
    }

    public void setOnChatUpdated(Consumer<TdApi.Object> onChatUpdated) {
        this.onChatUpdated = onChatUpdated;
    }

    public void setOnMessageReceived(Consumer<TdApi.Message> onMessageReceived) {
        this.onMessageReceived = onMessageReceived;
    }

    public void setOnConnectionStateUpdated(Consumer<TdApi.ConnectionState> onConnectionStateUpdated) {
        this.onConnectionStateUpdated = onConnectionStateUpdated;
    }
}
