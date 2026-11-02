package com.core.ethornell;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure JSON schema for Ethornell kind-5 host dialogs.
 *
 * <p>The worker fetches the request payload from the engine owner thread; this class only parses
 * the immutable {@code String} and builds responses. It has no Android dependencies, so payload
 * and response boundaries stay JVM-testable. Malformed payloads are rejected before any UI is
 * shown, and every accepted answer is serialized and validated once before it reaches native.
 */
public final class EthornellDialogProtocol {
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    public static final int MAX_SEGMENTS = 64;
    public static final int MAX_OPTIONS = 256;
    private static final int DATE_FIELDS = 4;
    private static final int[] MONTH_DAYS = {31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private static final String CANCEL = "{\"accepted\":false}";

    private EthornellDialogProtocol() {}

    /** Checked schema failure; callers must show a cancel-only UI or fall back to {@link #cancel()}. */
    public static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;
        public ProtocolException(String message) { super(message); }
        public ProtocolException(String message, Throwable cause) { super(message, cause); }
    }

    /** One text input field from an {@code input} or {@code date} request. */
    public static final class Field {
        public final String label;
        public final String initial;
        public final int maxBytes;
        public final boolean numeric;

        private Field(String label, String initial, int maxBytes, boolean numeric) {
            this.label = label;
            this.initial = initial;
            this.maxBytes = maxBytes;
            this.numeric = numeric;
        }
    }

    /** Immutable parsed request. Fields not belonging to the type stay empty or zero. */
    public static final class Request {
        public final String type;
        public final String title;
        public final String prompt;
        public final List<Field> fields;
        public final int segments;
        public final int maxBytes;
        public final boolean numeric;
        public final List<String> options;
        public final int month;
        public final int day;

        private Request(String type, String title, String prompt, List<Field> fields, int segments,
                        int maxBytes, boolean numeric, List<String> options, int month, int day) {
            this.type = type;
            this.title = title;
            this.prompt = prompt;
            this.fields = Collections.unmodifiableList(fields);
            this.segments = segments;
            this.maxBytes = maxBytes;
            this.numeric = numeric;
            this.options = Collections.unmodifiableList(options);
            this.month = month;
            this.day = day;
        }

        /**
         * Builds and validates an accepted answer. {@code index} is used by {@code select};
         * {@code month}/{@code day} are zero-based and used by {@code date}. Inputs belonging to
         * other types are ignored.
         */
        public String answer(String[] texts, int index, int month, int day) throws ProtocolException {
            JSONObject answer = new JSONObject();
            try {
                switch (type) {
                    case "input":
                        putTexts(answer, texts, fields.size());
                        break;
                    case "segmented":
                        putTexts(answer, texts, segments);
                        break;
                    case "select":
                        if (index < 0 || index >= options.size())
                            throw new ProtocolException("selection index outside options");
                        answer.put("index", index);
                        break;
                    case "date":
                        if (month < 0 || month >= MONTH_DAYS.length)
                            throw new ProtocolException("month outside 0..11");
                        if (day < 0 || day >= MONTH_DAYS[month])
                            throw new ProtocolException("day outside month");
                        putTexts(answer, texts, DATE_FIELDS);
                        answer.put("month", month);
                        answer.put("day", day);
                        break;
                    default:
                        throw new ProtocolException("unknown dialog type");
                }
                answer.put("accepted", true);
            } catch (JSONException e) {
                throw new ProtocolException("answer serialization failed", e);
            }
            String json = answer.toString();
            validateAnswer(this, json);
            return json;
        }

        /** Never sends a partial answer: any construction failure becomes an explicit cancel. */
        public String answerOrCancel(String[] texts, int index, int month, int day) {
            try {
                return answer(texts, index, month, day);
            } catch (ProtocolException | RuntimeException e) {
                return cancel();
            }
        }
    }

    /** Parses UTF-8 payload bytes and rejects malformed input, embedded NUL and oversize data. */
    public static Request parse(byte[] payload) throws ProtocolException {
        if (payload == null) throw new ProtocolException("payload missing");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new ProtocolException("payload too large");
        final String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(payload)).toString();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("payload is not valid UTF-8", e);
        }
        return parse(text);
    }

    /** Parses an already-decoded payload; UTF-8 encodability and size are still enforced. */
    public static Request parse(String payload) throws ProtocolException {
        if (payload == null) throw new ProtocolException("payload missing");
        if (payload.length() > MAX_PAYLOAD_BYTES) throw new ProtocolException("payload too large");
        checkText(payload, "payload");
        if (utf8Length(payload) > MAX_PAYLOAD_BYTES) throw new ProtocolException("payload too large");
        final JSONObject object;
        try {
            object = new JSONObject(payload);
        } catch (JSONException e) {
            throw new ProtocolException("payload is not a JSON object", e);
        }
        String type = string(object, "type", true, false);
        switch (type) {
            case "input": return input(object);
            case "segmented": return segmented(object);
            case "select": return select(object);
            case "date": return date(object);
            default: throw new ProtocolException("unknown dialog type");
        }
    }

    /** Explicit cancel value; never empty and never dependent on a missing field. */
    public static String cancel() { return CANCEL; }

    /** Re-parses an accepted answer and checks the schema for the original request. */
    public static void validateAnswer(Request request, String json) throws ProtocolException {
        if (request == null || json == null || json.isEmpty()) throw new ProtocolException("answer missing");
        if (json.length() > MAX_PAYLOAD_BYTES) throw new ProtocolException("answer too large");
        checkText(json, "answer");
        if (utf8Length(json) > MAX_PAYLOAD_BYTES) throw new ProtocolException("answer too large");
        final JSONObject object;
        try {
            object = new JSONObject(json);
        } catch (JSONException e) {
            throw new ProtocolException("answer is not a JSON object", e);
        }
        Object accepted = object.opt("accepted");
        if (!(accepted instanceof Boolean) || !((Boolean) accepted))
            throw new ProtocolException("answer is not accepted");
        switch (request.type) {
            case "input":
                checkTexts(object, request.fields.size());
                break;
            case "segmented":
                checkTexts(object, request.segments);
                break;
            case "select": {
                int index = integer(object, "index", true);
                if (index < 0 || index >= request.options.size())
                    throw new ProtocolException("selection index outside options");
                break;
            }
            case "date": {
                checkTexts(object, DATE_FIELDS);
                int month = integer(object, "month", true);
                int day = integer(object, "day", true);
                if (month < 0 || month >= MONTH_DAYS.length)
                    throw new ProtocolException("month outside 0..11");
                if (day < 0 || day >= MONTH_DAYS[month])
                    throw new ProtocolException("day outside month");
                break;
            }
            default:
                throw new ProtocolException("unknown dialog type");
        }
    }

    private static Request input(JSONObject object) throws ProtocolException {
        String title = string(object, "title", true, true);
        JSONArray array = array(object, "fields", true);
        int count = array.length();
        if (count < 1 || count > 2) throw new ProtocolException("input needs one or two fields");
        List<Field> fields = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            JSONObject item = object(array, i, "input field");
            fields.add(field(item));
        }
        return new Request("input", title, "", fields, 0, 0, false,
                Collections.<String>emptyList(), 0, 0);
    }

    private static Request segmented(JSONObject object) throws ProtocolException {
        String title = string(object, "title", true, true);
        String prompt = string(object, "prompt", false, true);
        int segments = integer(object, "segments", true);
        if (segments < 1 || segments > MAX_SEGMENTS)
            throw new ProtocolException("segments outside 1.." + MAX_SEGMENTS);
        int maxBytes = integer(object, "max_bytes", true);
        if (maxBytes < 0) throw new ProtocolException("negative max_bytes");
        boolean numeric = bool(object, "numeric", true);
        return new Request("segmented", title, prompt, Collections.<Field>emptyList(), segments,
                maxBytes, numeric, Collections.<String>emptyList(), 0, 0);
    }

    private static Request select(JSONObject object) throws ProtocolException {
        String title = string(object, "title", true, true);
        String prompt = string(object, "prompt", false, true);
        JSONArray array = array(object, "options", true);
        int count = array.length();
        if (count < 1 || count > MAX_OPTIONS)
            throw new ProtocolException("options outside 1.." + MAX_OPTIONS);
        List<String> options = new ArrayList<>(count);
        for (int i = 0; i < count; i++) options.add(string(array, i, "option"));
        return new Request("select", title, prompt, Collections.<Field>emptyList(), 0, 0, false,
                options, 0, 0);
    }

    private static Request date(JSONObject object) throws ProtocolException {
        String title = string(object, "title", false, true);
        JSONArray array = array(object, "fields", true);
        if (array.length() != DATE_FIELDS) throw new ProtocolException("date needs four fields");
        List<Field> fields = new ArrayList<>(DATE_FIELDS);
        for (int i = 0; i < DATE_FIELDS; i++)
            fields.add(new Field("", string(array, i, "date field"), 10, false));
        int month = integer(object, "month", true);
        int day = integer(object, "day", true);
        if (month < 0 || month >= MONTH_DAYS.length)
            throw new ProtocolException("month outside 0..11");
        if (day < 0 || day >= MONTH_DAYS[month]) throw new ProtocolException("day outside month");
        return new Request("date", title, "", fields, 0, 0, false,
                Collections.<String>emptyList(), month, day);
    }

    private static Field field(JSONObject object) throws ProtocolException {
        String label = string(object, "label", true, true);
        String initial = string(object, "initial", true, true);
        int maxBytes = integer(object, "max_bytes", true);
        if (maxBytes < 0) throw new ProtocolException("negative max_bytes");
        boolean numeric = bool(object, "numeric", true);
        return new Field(label, initial, maxBytes, numeric);
    }

    private static void putTexts(JSONObject answer, String[] texts, int expected)
            throws ProtocolException, JSONException {
        if (texts == null || texts.length != expected)
            throw new ProtocolException("text count does not match request");
        JSONArray array = new JSONArray();
        int total = 0;
        for (String text : texts) {
            if (text == null) throw new ProtocolException("text missing");
            checkText(text, "text");
            total += utf8Length(text);
            if (total > MAX_PAYLOAD_BYTES) throw new ProtocolException("answer too large");
            array.put(text);
        }
        answer.put("texts", array);
    }

    private static void checkTexts(JSONObject object, int expected) throws ProtocolException {
        JSONArray array = array(object, "texts", true);
        if (array.length() != expected) throw new ProtocolException("text count does not match request");
        for (int i = 0; i < expected; i++) checkText(string(array, i, "text"), "text");
    }

    private static JSONArray array(JSONObject object, String key, boolean required)
            throws ProtocolException {
        Object value = object.opt(key);
        if (!(value instanceof JSONArray)) {
            if (!required && value == null) return new JSONArray();
            throw new ProtocolException("missing array " + key);
        }
        return (JSONArray) value;
    }

    private static JSONObject object(JSONArray array, int index, String what)
            throws ProtocolException {
        Object value = array.opt(index);
        if (!(value instanceof JSONObject)) throw new ProtocolException("invalid " + what);
        return (JSONObject) value;
    }

    private static String string(JSONObject object, String key, boolean required, boolean allowEmpty)
            throws ProtocolException {
        Object value = object.opt(key);
        if (value == null) {
            if (required) throw new ProtocolException("missing string " + key);
            return "";
        }
        if (!(value instanceof String)) throw new ProtocolException("invalid string " + key);
        String text = (String) value;
        checkText(text, key);
        if (!allowEmpty && text.isEmpty()) throw new ProtocolException("empty string " + key);
        return text;
    }

    private static String string(JSONArray array, int index, String what) throws ProtocolException {
        Object value = array.opt(index);
        if (!(value instanceof String)) throw new ProtocolException("invalid " + what);
        String text = (String) value;
        checkText(text, what);
        return text;
    }

    private static int integer(JSONObject object, String key, boolean required)
            throws ProtocolException {
        Object value = object.opt(key);
        if (value == null) {
            if (required) throw new ProtocolException("missing integer " + key);
            return 0;
        }
        if (!(value instanceof Integer) && !(value instanceof Long))
            throw new ProtocolException("invalid integer " + key);
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
            throw new ProtocolException("integer outside range " + key);
        return (int) number;
    }

    private static boolean bool(JSONObject object, String key, boolean required)
            throws ProtocolException {
        Object value = object.opt(key);
        if (value == null) {
            if (required) throw new ProtocolException("missing boolean " + key);
            return false;
        }
        if (!(value instanceof Boolean)) throw new ProtocolException("invalid boolean " + key);
        return (Boolean) value;
    }

    private static void checkText(String text, String what) throws ProtocolException {
        if (text == null) throw new ProtocolException("missing " + what);
        if (text.indexOf('\0') >= 0) throw new ProtocolException("embedded NUL in " + what);
        try {
            utf8(text);
        } catch (CharacterCodingException e) {
            throw new ProtocolException("invalid UTF-8 in " + what, e);
        }
    }

    private static int utf8Length(String text) throws ProtocolException {
        if (text.length() > MAX_PAYLOAD_BYTES) throw new ProtocolException("text too large");
        try {
            return utf8(text).remaining();
        } catch (CharacterCodingException e) {
            throw new ProtocolException("invalid UTF-8", e);
        }
    }

    private static ByteBuffer utf8(String text) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(text));
    }
}
