package com.gaiys.proto.prototypehub.service;

import static org.junit.jupiter.api.Assertions.*;

import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PrototypeCopyPlainTextTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "\u0000",
                "before\u0000after",
                "\ud800",
                "\udc00",
                "\ud800x",
                "x\udc00",
                "\ud800\ud800",
                "\udc00\ud800",
                "😀\ud800"
            })
    void rejectsNulAndUnpairedUtf16WithExplicit400(String value) {
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () -> PrototypeCopyInventoryService.plain(value, 2000));
        assertEquals(400, error.getStatus());
        assertEquals("PROTOTYPE_HUB_COPY_INVENTORY_INPUT_INVALID", error.getCode());
    }

    @Test
    void preservesLegalTextWithoutTrimmingNormalizationOrFormulaEscaping() {
        String original = " \t=SUM(1,2)\r\n中文 \"quoted\" \\ path 😀 e\u0301\u00a0 ";
        assertEquals(original, PrototypeCopyInventoryService.plain(original, original.length()));
        assertEquals("", PrototypeCopyInventoryService.plain(null, 0));
        assertEquals("", PrototypeCopyInventoryService.plain("", 0));
        assertEquals(" \n ", PrototypeCopyInventoryService.plain(" \n ", 3));
    }

    @Test
    void enforcesUtf16UnitsAtTheBoundaryWithoutTruncatingASupplementaryCharacter() {
        String original = "x".repeat(1998) + "😀";
        assertEquals(2000, original.length());
        assertEquals(original, PrototypeCopyInventoryService.plain(original, 2000));
        var error =
                assertThrows(
                        PrototypeHubException.class,
                        () -> PrototypeCopyInventoryService.plain(original, 1999));
        assertEquals(400, error.getStatus());
        assertEquals("PROTOTYPE_HUB_COPY_INVENTORY_INPUT_INVALID", error.getCode());
    }
}
