package com.gaiys.proto.prototypehub.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
import com.gaiys.proto.prototypehub.web.info.ReviewElementTarget;
import com.gaiys.proto.prototypehub.web.request.ReviewThreadCreateRequest;
import jakarta.validation.Validation;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewElementTargetTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode target() throws Exception {
        return (ObjectNode)
                mapper.readTree(
                        """
                {"space":"frame-element-v1","frames":[{"elementId":"u1_input","resourcePath":"pages/child.html"}],
                 "elementId":"u2","tagName":"div","textHint":"确认 Confirm","viewportWidth":390,"viewportHeight":600}
                """);
    }

    @Test
    void immutableSnapshotAndCanonicalIgnoreNestedJsonOrder() throws Exception {
        var value = mapper.treeToValue(target(), ReviewElementTarget.class);
        var reordered =
                mapper.readTree(
                        """
                {"viewportHeight":600,"viewportWidth":390,"textHint":"确认 Confirm","tagName":"div","elementId":"u2",
                 "frames":[{"resourcePath":"pages/child.html","elementId":"u1_input"}],"space":"frame-element-v1"}
                """);
        assertEquals(
                value.canonical(),
                mapper.treeToValue(reordered, ReviewElementTarget.class).canonical());
        assertEquals(
                value,
                mapper.readValue(mapper.writeValueAsBytes(value), ReviewElementTarget.class));
        var frames = new ArrayList<>(value.frames());
        var snapshot =
                new ReviewElementTarget(
                        value.space(),
                        frames,
                        value.elementId(),
                        value.tagName(),
                        value.textHint(),
                        390,
                        600);
        frames.clear();
        assertEquals(value, snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.frames().clear());
        var root = target();
        root.putArray("frames");
        assertTrue(mapper.treeToValue(root, ReviewElementTarget.class).frames().isEmpty());
    }

    @Test
    void rejectsUnknownMissingCoercedAndOutOfBoundsMetadata() throws Exception {
        for (String name :
                List.of(
                        "space",
                        "frames",
                        "elementId",
                        "tagName",
                        "textHint",
                        "viewportWidth",
                        "viewportHeight")) {
            var value = target();
            value.remove(name);
            invalid(value);
        }
        invalid(target().put("selector", "#u2"));
        for (String field : List.of("viewportWidth", "viewportHeight")) {
            for (String replacement :
                    List.of("\"390\"", "390.0", "true", "null", "0", "16385", "2147483648")) {
                var value = target();
                value.set(field, mapper.readTree(replacement));
                invalid(value);
            }
        }
        for (String id : List.of("", "1first", "has space", "u".repeat(129), "中文"))
            invalid(target().put("elementId", id));
        for (String tag : List.of("DIV", "a-b", "", "a".repeat(33)))
            invalid(target().put("tagName", tag));
        for (String hint :
                List.of("x".repeat(161), "line\nnext", "nul\u0000", "tab\t", "\uD800", "\uDC00"))
            invalid(target().put("textHint", hint));
        assertEquals(
                "😀".repeat(80),
                mapper.treeToValue(
                                target().put("textHint", "😀".repeat(80)),
                                ReviewElementTarget.class)
                        .textHint());
        var longFrames = target();
        var frames = longFrames.withArray("frames");
        for (int i = 0; i < 4; i++) frames.add(frames.get(0).deepCopy());
        invalid(longFrames);
        var unknownFrame = target();
        ((ObjectNode) unknownFrame.withArray("frames").get(0)).put("html", "<iframe>");
        invalid(unknownFrame);
        var nullFrame = target();
        nullFrame.withArray("frames").addNull();
        invalid(nullFrame);
    }

    @Test
    void encodedFramePathsAreRelativeBoundedAndDoNotEscapeSegments() throws Exception {
        for (String path :
                List.of(
                        "",
                        "/root.html",
                        "a//b.html",
                        "a/",
                        ".",
                        "..",
                        "a/../b",
                        "a/%2e/b",
                        "a/%2E%2e/b",
                        "a%2Fb",
                        "a%5cb",
                        "a\\b",
                        "a?x=1",
                        "a#b",
                        "a b",
                        "a\tb",
                        "a%00b",
                        "a%7fb",
                        "a%",
                        "a%2",
                        "a%GG",
                        "a%E4",
                        "a%C0%AF",
                        "中文.html",
                        "https:child.html",
                        "a".repeat(4097))) {
            var value = target();
            ((ObjectNode) value.withArray("frames").get(0)).put("resourcePath", path);
            invalid(value);
        }
        for (String path :
                List.of(
                        "child.html",
                        "pages/%E4%B8%AD%E6%96%87.html",
                        "a%20b.html",
                        "a+b.html",
                        "a%252Fb.html",
                        "a/%3Ab.html")) {
            var value = target();
            ((ObjectNode) value.withArray("frames").get(0)).put("resourcePath", path);
            assertEquals(
                    path,
                    mapper.treeToValue(value, ReviewElementTarget.class)
                            .frames()
                            .getFirst()
                            .resourcePath());
        }
    }

    @Test
    void createAllowsExactlyOnePositionAndDoesNotSerializeValidationProperty() throws Exception {
        var request = new ReviewThreadCreateRequest();
        request.setRevisionId("r");
        request.setPageId("a".repeat(64));
        request.setText("comment");
        request.setClientRequestId("request");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(request).isEmpty());
            request.setElementTarget(mapper.treeToValue(target(), ReviewElementTarget.class));
            assertTrue(validator.validate(request).isEmpty());
            request.setAnchor(
                    new ReviewAnchor(
                            "root-document-css-px-v1", "point", 1, 1, 0, 0, 100, 100, 390, 600));
            assertEquals(1, validator.validate(request).size());
            request.setElementTarget(null);
            assertTrue(validator.validate(request).isEmpty());
        }
        assertFalse(mapper.valueToTree(request).has("positionExclusive"));
    }

    private void invalid(ObjectNode value) {
        assertThrows(
                Exception.class,
                () -> mapper.treeToValue(value, ReviewElementTarget.class),
                value.toString());
    }
}
