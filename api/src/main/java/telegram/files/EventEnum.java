package telegram.files;

public enum EventEnum {

    /**
     * suffix = SettingRecord.key <br>
     * body = SettingRecord.value
     *
     * @see telegram.files.repository.SettingRecord
     */
    SETTING_UPDATE,

    /**
     * suffix = null <br>
     * body = SettingAutoRecords
     *
     * @see telegram.files.repository.SettingAutoRecords
     */
    AUTO_DOWNLOAD_UPDATE,

    /**
     * suffix = null <br>
     * body = JSONObject with "telegramId", "chatId", "messageId"
     */
    MESSAGE_RECEIVED,

    /**
     * suffix = null <br>
     * body = JSONObject with "telegramId", "payload"
     *
     * @see telegram.files.EventPayload
     */
    TELEGRAM_EVENT,

    /**
     * suffix = null <br>
     * body = JSONObject with "telegramId", "uniqueId" of a (non-thumbnail) file whose download completed
     */
    FILE_DOWNLOADED,

    /**
     * suffix = null <br>
     * body = JSONObject with "success", "message"
     */
    MAINTAIN,
    ;

    public String address() {
        return name();
    }

    public String address(String suffix) {
        return name() + "." + suffix;
    }
}
