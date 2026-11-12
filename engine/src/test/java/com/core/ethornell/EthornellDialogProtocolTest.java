package com.core.ethornell;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class EthornellDialogProtocolTest {
    private static final String INPUT = "{\"type\":\"input\",\"title\":\"Name\",\"fields\":["
            + "{\"label\":\"Family\",\"initial\":\"山田\",\"max_bytes\":4,\"numeric\":false},"
            + "{\"label\":\"Age\",\"initial\":\"\",\"max_bytes\":3,\"numeric\":true}]}";
    private static final String SEGMENTED = "{\"type\":\"segmented\",\"title\":\"Code\","
            + "\"prompt\":\"Enter\",\"segments\":3,\"max_bytes\":2,\"numeric\":false}";
    private static final String SELECT = "{\"type\":\"select\",\"title\":\"Pick\","
            + "\"prompt\":\"Choose\",\"options\":[\"A\",\"B\",\"C\"]}";
    private static final String DATE = "{\"type\":\"date\","
            + "\"fields\":[\"Y\",\"M\",\"D\",\"W\"],\"month\":11,\"day\":30}";

    @Test public void parsesInputAndPreservesFieldOrder() throws Exception {
        EthornellDialogProtocol.Request request = EthornellDialogProtocol.parse(INPUT);
        assertEquals("input", EthornellDialogProtocol.parse(
                INPUT.getBytes(java.nio.charset.StandardCharsets.UTF_8)).type);
        assertEquals("input", request.type);
        assertEquals("Name", request.title);
        assertEquals(2, request.fields.size());
        assertEquals("山田", request.fields.get(0).initial);
        assertFalse(request.fields.get(0).numeric);
        assertTrue(request.fields.get(1).numeric);

        String answer = request.answer(new String[]{"山田太郎", "-1a2b3"}, 0, 0, 0);
        JSONObject object = new JSONObject(answer);
        assertTrue(object.getBoolean("accepted"));
        JSONArray texts = object.getJSONArray("texts");
        assertEquals(2, texts.length());
        assertEquals("山田太郎", texts.getString(0));
        assertEquals("-1a2b3", texts.getString(1));
        EthornellDialogProtocol.validateAnswer(request, answer);
    }

    @Test public void oneFieldInputKeepsUnicodeAndSpaces() throws Exception {
        String payload = "{\"type\":\"input\",\"title\":\"Name\",\"fields\":["
                + "{\"label\":\"Name\",\"initial\":\"\",\"max_bytes\":32,\"numeric\":false}]}";
        EthornellDialogProtocol.Request request = EthornellDialogProtocol.parse(payload);
        String answer = request.answer(new String[]{" 名前😀 "}, 0, 0, 0);
        assertEquals(" 名前😀 ", new JSONObject(answer).getJSONArray("texts").getString(0));
    }

    @Test public void segmentedAnswersOneTextPerSegment() throws Exception {
        EthornellDialogProtocol.Request request = EthornellDialogProtocol.parse(SEGMENTED);
        assertEquals(3, request.segments);
        String answer = request.answer(new String[]{"ab", "cd", "ef"}, 0, 0, 0);
        JSONObject object = new JSONObject(answer);
        assertTrue(object.getBoolean("accepted"));
        assertEquals(3, object.getJSONArray("texts").length());
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> request.answer(new String[]{"ab", "cd"}, 0, 0, 0));
        assertEquals(EthornellDialogProtocol.cancel(), request.answerOrCancel(new String[]{"ab"}, 0, 0, 0));
    }

    @Test public void selectionUsesZeroBasedOptionIndex() throws Exception {
        EthornellDialogProtocol.Request request = EthornellDialogProtocol.parse(SELECT);
        assertEquals(3, request.options.size());
        JSONObject first = new JSONObject(request.answer(null, 0, 0, 0));
        assertTrue(first.getBoolean("accepted"));
        assertEquals(0, first.getInt("index"));
        assertEquals(2, new JSONObject(request.answer(null, 2, 0, 0)).getInt("index"));
        assertThrows(EthornellDialogProtocol.ProtocolException.class, () -> request.answer(null, -1, 0, 0));
        assertThrows(EthornellDialogProtocol.ProtocolException.class, () -> request.answer(null, 3, 0, 0));
        assertEquals(EthornellDialogProtocol.cancel(), request.answerOrCancel(null, 3, 0, 0));
    }

    @Test public void dateUsesZeroBasedValuesWhileUiShowsOneBased() throws Exception {
        EthornellDialogProtocol.Request request = EthornellDialogProtocol.parse(DATE);
        assertEquals(11, request.month);
        assertEquals(30, request.day);
        assertEquals(4, request.fields.size());

        int shownMonth = request.month + 1;
        int shownDay = request.day + 1;
        String answer = request.answer(new String[]{"2026", "12", "31", "Thu"},
               0, shownMonth - 1, shownDay - 1);
        JSONObject object = new JSONObject(answer);
        assertTrue(object.getBoolean("accepted"));
        assertEquals(11, object.getInt("month"));
        assertEquals(30, object.getInt("day"));
        assertEquals(4, object.getJSONArray("texts").length());

        JSONObject start = new JSONObject(request.answer(new String[]{"a", "b", "c", "d"}, 0, 0, 0));
        assertEquals(0, start.getInt("month"));
        assertEquals(0, start.getInt("day"));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> request.answer(new String[]{"a", "b", "c", "d"}, 0, 12, 0));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> request.answer(new String[]{"a", "b", "c", "d"}, 0, 1, 30));
        assertEquals(EthornellDialogProtocol.cancel(),
                request.answerOrCancel(new String[]{"a", "b", "c", "d"}, 0, -1, 0));
    }

    @Test public void cancelIsExplicitAndCanonical() throws Exception {
        assertEquals("{\"accepted\":false}", EthornellDialogProtocol.cancel());
        assertFalse(new JSONObject(EthornellDialogProtocol.cancel()).getBoolean("accepted"));
    }

    @Test public void rejectsMalformedAndUnknownRequests() {
        for (String payload : new String[]{
                null, "", "nope", "[]", "{}",
                "{\"type\":\"unknown\",\"title\":\"x\"}",
                "{\"type\":1,\"title\":\"x\"}",
                "{\"type\":\"input\",\"title\":\"x\",\"fields\":[]}",
                "{\"type\":\"input\",\"title\":\"x\",\"fields\":[{}, {}, {}]}",
                "{\"type\":\"input\",\"title\":\"x\",\"fields\":[{\"label\":\"a\",\"initial\":\"\",\"max_bytes\":1}]}",
                "{\"type\":\"input\",\"title\":\"x\",\"fields\":[{\"label\":\"a\",\"initial\":\"\",\"max_bytes\":-1,\"numeric\":false}]}",
                "{\"type\":\"segmented\",\"title\":\"x\",\"segments\":0,\"max_bytes\":1,\"numeric\":false}",
                "{\"type\":\"segmented\",\"title\":\"x\",\"segments\":65,\"max_bytes\":1,\"numeric\":false}",
                "{\"type\":\"select\",\"title\":\"x\",\"options\":[]}",
                "{\"type\":\"select\",\"title\":\"x\",\"options\":[1,2]}",
                "{\"type\":\"date\",\"fields\":[\"a\",\"b\",\"c\"],\"month\":0,\"day\":0}",
                "{\"type\":\"date\",\"fields\":[\"a\",\"b\",\"c\",\"d\",\"e\"],\"month\":0,\"day\":0}",
                "{\"type\":\"date\",\"fields\":[\"a\",\"b\",\"c\",\"d\"],\"month\":12,\"day\":0}",
                "{\"type\":\"date\",\"fields\":[\"a\",\"b\",\"c\",\"d\"],\"month\":1,\"day\":29}",
                "{\"type\":\"input\",\"title\":3,\"fields\":[{\"label\":\"a\",\"initial\":\"\",\"max_bytes\":1,\"numeric\":false}]}",
                "{\"type\":\"input\",\"title\":\"x\",\"fields\":[{\"label\":\"a\",\"initial\":\"\",\"max_bytes\":1.5,\"numeric\":false}]}"})
            assertThrows(payload, EthornellDialogProtocol.ProtocolException.class,
                    () -> EthornellDialogProtocol.parse(payload));
    }

    @Test public void enforcesSegmentAndOptionCapsAtTheBoundary() throws Exception {
        assertEquals(EthornellDialogProtocol.MAX_SEGMENTS,
                EthornellDialogProtocol.parse(segmented(EthornellDialogProtocol.MAX_SEGMENTS)).segments);
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.parse(segmented(EthornellDialogProtocol.MAX_SEGMENTS + 1)));
        EthornellDialogProtocol.Request options =
                EthornellDialogProtocol.parse(select(EthornellDialogProtocol.MAX_OPTIONS));
        assertEquals(EthornellDialogProtocol.MAX_OPTIONS, options.options.size());
        assertEquals(EthornellDialogProtocol.MAX_OPTIONS - 1,
                new JSONObject(options.answer(null, EthornellDialogProtocol.MAX_OPTIONS - 1, 0, 0)).getInt("index"));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.parse(select(EthornellDialogProtocol.MAX_OPTIONS + 1)));
    }

    @Test public void rejectsNulInvalidUtf8AndOversizePayloads() {
        String nulInTitle = "{\"type\":\"input\",\"title\":\"a\\u0000b\",\"fields\":["
                + "{\"label\":\"a\",\"initial\":\"\",\"max_bytes\":1,\"numeric\":false}]}";
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.parse(nulInTitle));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.parse(INPUT + "\u0000"));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.parse(new byte[]{(byte) 0xC3, 0x28}));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.parse(new byte[EthornellDialogProtocol.MAX_PAYLOAD_BYTES + 1]));
        String oversize = "{\"type\":\"input\",\"title\":\""
                + "x".repeat(EthornellDialogProtocol.MAX_PAYLOAD_BYTES) + "\",\"fields\":[]}";
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.parse(oversize));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.parse("{\"type\":\"input\",\"title\":\""
                        + Character.toString((char) 0xD800) + "\",\"fields\":[]}"));
    }

    @Test public void answerConstructionRejectsNulOversizeAndSurrogatesThenFallsBackToCancel() throws Exception {
        EthornellDialogProtocol.Request request = EthornellDialogProtocol.parse(INPUT);
        String nul = "a" + Character.toString((char) 0) + "b";
        String lone = Character.toString((char) 0xD800);
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> request.answer(new String[]{nul, ""}, 0, 0, 0));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> request.answer(new String[]{lone, ""}, 0, 0, 0));
        String huge = "x".repeat(EthornellDialogProtocol.MAX_PAYLOAD_BYTES + 1);
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> request.answer(new String[]{huge, ""}, 0, 0, 0));
        assertEquals(EthornellDialogProtocol.cancel(), request.answerOrCancel(new String[]{huge, ""}, 0, 0, 0));
        assertEquals(EthornellDialogProtocol.cancel(), request.answerOrCancel(new String[]{nul, ""}, 0, 0, 0));
    }

    @Test public void answerValidatorRejectsMissingOrOutOfRangeValues() throws Exception {
        EthornellDialogProtocol.Request input = EthornellDialogProtocol.parse(INPUT);
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.validateAnswer(input, "{\"texts\":[\"a\",\"b\"]}"));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.validateAnswer(input, "{\"accepted\":false}"));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.validateAnswer(input, "{\"accepted\":true,\"texts\":[\"a\"]}"));
        EthornellDialogProtocol.Request select = EthornellDialogProtocol.parse(SELECT);
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.validateAnswer(select, "{\"accepted\":true,\"index\":\"1\"}"));
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.validateAnswer(select, "{\"accepted\":true,\"index\":3}"));
        EthornellDialogProtocol.Request date = EthornellDialogProtocol.parse(DATE);
        assertThrows(EthornellDialogProtocol.ProtocolException.class,
                () -> EthornellDialogProtocol.validateAnswer(date,
                        "{\"accepted\":true,\"texts\":[\"a\",\"b\",\"c\",\"d\"],\"month\":11,\"day\":31}"));
    }

    private static String segmented(int count) {
        return "{\"type\":\"segmented\",\"title\":\"x\",\"segments\":" + count
                + ",\"max_bytes\":1,\"numeric\":false}";
    }

    private static String select(int count) {
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) options.append(',');
            options.append('"').append(i).append('"');
        }
        return "{\"type\":\"select\",\"title\":\"x\",\"options\":[" + options + "]}";
    }
}
