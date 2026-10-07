package telegram.files;

import cn.hutool.core.util.ReUtil;
import org.apache.commons.jexl3.JexlExpression;
import org.drinkless.tdlib.TdApi;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageFilterTest {

    private static List<TdApi.Message> messages;

    @BeforeAll
    static void setup() {
        TdApi.Message msg1 = new TdApi.Message();
        msg1.id = 1;
        msg1.content = new TdApi.MessageText(
                new TdApi.FormattedText("Hello World", null),
                null,
                null
        );

        TdApi.Message msg2 = new TdApi.Message();
        msg2.id = 2;
        msg2.content = new TdApi.MessageText(
                new TdApi.FormattedText("Test Message", null),
                null,
                null
        );

        TdApi.Message msg3 = new TdApi.Message();
        msg3.id = 3;
        msg3.content = new TdApi.MessageText(
                new TdApi.FormattedText("Another Hello", null),
                null,
                null
        );

        messages = Arrays.asList(msg1, msg2, msg3);
    }

    @Test
    void testFilterByTextContains() {
        String expr = "str:contains(content.text.text, 'Hello')";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(m -> m.content instanceof TdApi.MessageText &&
                                                   ((TdApi.MessageText) m.content).text.text.contains("Hello")));
    }

    @Test
    void testSandboxBlocksDangerousCalls() {
        // Each of these reached reflection, deserialization, process execution or the filesystem upstream.
        for (String expr : List.of(
                "class:loadClass('java.lang.Runtime') != null",
                "obj:isNull(null)",
                "zip:zip('/tmp') != null",
                "net:getLocalhostStr() != null",
                "str:contains(content.text.text, 'Hello') && ''.class.forName('java.lang.Runtime') != null",
                "new('cn.hutool.core.util.RuntimeUtil') != null")) {
            assertTrue(MessageFilter.filter(messages, expr).isEmpty(), expr);
        }
        // ...while the documented helpers keep working.
        assertEquals(2, MessageFilter.filter(messages, "re:isMatch('.*Hello.*', content.text.text)").size());
    }

    @Test
    void testFilterById() {
        String expr = "id > 1";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(m -> m.id > 1));
    }

    @Test
    void testInvalidExpression() {
        String expr = "invalidFunc(content.text.text)";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(0, filtered.size());
    }

    @Test
    void testExpressionCache() {
        String expr = "id > 0";
        JexlExpression expression = MessageFilter.getExpression(expr);
        assertEquals(expression, MessageFilter.getExpression(expr), "Expression should be cached and identical");
    }

    @Test
    void testFilterByRegex() {
        assertTrue(ReUtil.isMatch("Test.*", "Test Message"));
        String expr = "re:isMatch('Test.*', content.text.text)";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(1, filtered.size());
        assertInstanceOf(TdApi.MessageText.class, filtered.getFirst().content);
        assertEquals("Test Message", ((TdApi.MessageText) filtered.getFirst().content).text.text);
    }

    @Test
    void testFilterByVideoSize() {
        TdApi.Message videoMsg = new TdApi.Message();
        videoMsg.id = 4;
        TdApi.Video video = new TdApi.Video();
        video.video = new TdApi.File();
        video.video.size = 5000;
        video.video.remote = new TdApi.RemoteFile();
        videoMsg.content = new TdApi.MessageVideo();
        ((TdApi.MessageVideo) videoMsg.content).video = video;
        ((TdApi.MessageVideo) videoMsg.content).caption = new TdApi.FormattedText();
        List<TdApi.Message> videoMessages = List.of(videoMsg);

        String expr = "content.video.video.size > 4000";
        List<TdApi.Message> filtered = MessageFilter.filter(videoMessages, expr);
        assertEquals(1, filtered.size());
        assertEquals(4, filtered.getFirst().id);

        // Test use of 'f' alias for file
        String exprAlias = "f.size() > 4000";
        List<TdApi.Message> filteredAlias = MessageFilter.filter(videoMessages, exprAlias);
        assertEquals(1, filteredAlias.size());
        assertEquals(4, filteredAlias.getFirst().id);
    }

    @Test
    void testSecurityAgainstCodeInjection() {
        String expr = "java.lang.System.exit(0)";
        List<TdApi.Message> filtered = MessageFilter.filter(messages, expr);

        assertEquals(0, filtered.size(), "No messages should be returned for malicious expressions");
    }
}
