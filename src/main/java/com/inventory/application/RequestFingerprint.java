package com.inventory.application;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Versioned, unambiguous fingerprints for JSON requests within an explicit business scope. */
public final class RequestFingerprint {
    private static final int MAX_JSON_CHARACTERS = 1_000_000;
    private static final int MAX_DEPTH = 100;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private RequestFingerprint() {}

    /** Scope is exact and must include the caller's desired key namespace (e.g. tenant and operation). */
    public static String sha256(String scope, String json) {
        if (scope == null || scope.isBlank()) throw new IllegalArgumentException("scope is required");
        if (json == null || json.length() > MAX_JSON_CHARACTERS) {
            throw new IllegalArgumentException("JSON is required and must fit the size limit");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null) throw new IllegalArgumentException("JSON is required");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
                writeString(out, "inventory-request-fingerprint-v1");
                writeString(out, scope);
                writeNode(out, root, 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid JSON", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void writeNode(DataOutputStream out, JsonNode node, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting limit exceeded");
        if (node.isObject()) {
            out.writeByte('O');
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            fields.forEachRemaining(field -> sorted.put(field.getKey(), field.getValue()));
            out.writeInt(sorted.size());
            for (Map.Entry<String, JsonNode> field : sorted.entrySet()) {
                writeString(out, field.getKey());
                writeNode(out, field.getValue(), depth + 1);
            }
        } else if (node.isArray()) {
            out.writeByte('A');
            out.writeInt(node.size());
            for (JsonNode item : node) writeNode(out, item, depth + 1);
        } else if (node.isTextual()) {
            out.writeByte('S');
            writeString(out, node.textValue());
        } else if (node.isNumber()) {
            out.writeByte('N');
            BigDecimal number = node.decimalValue();
            writeString(out, number.signum() == 0 ? "0" : number.stripTrailingZeros().toString());
        } else if (node.isBoolean()) {
            out.writeByte(node.booleanValue() ? 'T' : 'F');
        } else if (node.isNull()) {
            out.writeByte('Z');
        } else {
            throw new IllegalArgumentException("unsupported JSON value");
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        // UTF-8 encoding replaces unpaired surrogates, so reject them to avoid distinct strings colliding.
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("invalid Unicode string");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("invalid Unicode string");
            }
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
