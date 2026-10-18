package com.inventory.application;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {
    private static String fingerprint(String scope, String json) {
        return RequestFingerprint.sha256(scope, json);
    }

    @Test
    void normalizesObjectOrderAndMathematicallyEqualNumbers() {
        String first = "{\"sku\":\"상품\",\"quantity\":1,\"details\":{\"x\":1000,\"y\":-0.0}}";
        String second = " { \"details\": { \"y\":0, \"x\":1.000e3 }, \"quantity\":1.0, \"sku\":\"상품\" } ";
        assertEquals(fingerprint("outbound", first), fingerprint("outbound", second));
        assertEquals(64, fingerprint("outbound", first).length());
    }

    @Test
    void keepsArrayOrderScopeTypesAndValuesDistinct() {
        assertNotEquals(fingerprint("outbound", "[1,2]"), fingerprint("outbound", "[2,1]"));
        assertNotEquals(fingerprint("outbound", "{\"x\":1}"), fingerprint("return", "{\"x\":1}"));
        assertNotEquals(fingerprint("outbound", "1"), fingerprint("outbound", "\"1\""));
        assertNotEquals(fingerprint("outbound", "true"), fingerprint("outbound", "false"));
        assertNotEquals(fingerprint("outbound", "null"), fingerprint("outbound", "\"null\""));
        assertNotEquals(fingerprint("outbound", "{\"a\":\"bc\"}"), fingerprint("outbound", "{\"ab\":\"c\"}"));
        assertNotEquals(fingerprint("outbound", "[1,2]"), fingerprint("outbound", "[1,3]"));
    }

    @Test
    void handlesHighPrecisionWithoutConvertingThroughBinaryFloatingPoint() {
        String value = "9007199254740993.12345678901234567890";
        assertEquals(fingerprint("scope", value), fingerprint("scope", "9007199254740993.1234567890123456789"));
        assertNotEquals(fingerprint("scope", value), fingerprint("scope", "9007199254740993.12345678901234567891"));
        assertEquals(fingerprint("scope", "100000000000000000000000000000"), fingerprint("scope", "1e29"));
    }

    @Test
    void rejectsAmbiguousOrInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> fingerprint(" ", "{}"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint(null, "{}"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("scope", null));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("scope", ""));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("scope", "{\"x\":1,\"x\":2}"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("scope", "{} {}"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("scope", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("scope", "\"\\uD800\""));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("\uD800", "{}"));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("scope", "[".repeat(102) + "0" + "]".repeat(102)));
        assertThrows(IllegalArgumentException.class, () -> fingerprint("scope", " ".repeat(1_000_001)));
    }

    @Test
    void preservesValidSupplementaryUnicodeCharacters() {
        assertEquals(fingerprint("scope", "\"😀\""), fingerprint("scope", "\"\\uD83D\\uDE00\""));
    }
}
