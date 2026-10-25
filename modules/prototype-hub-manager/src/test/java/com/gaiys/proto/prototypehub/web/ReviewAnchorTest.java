package com.gaiys.proto.prototypehub.web;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gaiys.proto.prototypehub.web.info.ReviewAnchor;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewAnchorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode anchor() throws Exception {
        return (ObjectNode)
                mapper.readTree(
                        """
                {"space":"root-document-css-px-v1","shape":"rect","x":4,"y":5,
                "width":20,"height":30,"documentWidth":100,"documentHeight":200,
                "viewportWidth":80,"viewportHeight":90}
                """);
    }

    @Test
    void roundTripsTypedShapesAndCanonicalOrder() throws Exception {
        for (String shape : List.of("point", "rect", "ellipse")) {
            ObjectNode value = anchor().put("shape", shape);
            if (shape.equals("point")) value.put("width", 0).put("height", 0);
            ReviewAnchor parsed = mapper.treeToValue(value, ReviewAnchor.class);
            assertEquals(
                    parsed, mapper.readValue(mapper.writeValueAsBytes(parsed), ReviewAnchor.class));
            assertEquals(shape, parsed.shape());
            var reordered = mapper.createObjectNode();
            var names = new java.util.ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            java.util.Collections.reverse(names);
            names.forEach(name -> reordered.set(name, value.get(name)));
            assertEquals(
                    parsed.canonical(),
                    mapper.treeToValue(reordered, ReviewAnchor.class).canonical());
        }
    }

    @Test
    void rejectsAllNumericCoercionsMissingAndUnknownFields() throws Exception {
        for (String field :
                List.of(
                        "x",
                        "y",
                        "width",
                        "height",
                        "documentWidth",
                        "documentHeight",
                        "viewportWidth",
                        "viewportHeight")) {
            for (String replacement :
                    List.of("\"1\"", "1.0", "1.5", "true", "null", "2147483648")) {
                var value = anchor();
                value.set(field, mapper.readTree(replacement));
                assertThrows(
                        Exception.class,
                        () -> mapper.treeToValue(value, ReviewAnchor.class),
                        field + replacement);
            }
            var missing = anchor();
            missing.remove(field);
            assertThrows(Exception.class, () -> mapper.treeToValue(missing, ReviewAnchor.class));
        }
        var unknown = anchor().put("selector", "ignored");
        assertThrows(Exception.class, () -> mapper.treeToValue(unknown, ReviewAnchor.class));
        assertThrows(Exception.class, () -> mapper.readValue("[]", ReviewAnchor.class));
        assertThrows(
                Exception.class,
                () -> mapper.treeToValue(anchor().put("space", 1), ReviewAnchor.class));
        assertThrows(
                Exception.class,
                () -> mapper.treeToValue(anchor().put("shape", true), ReviewAnchor.class));
    }

    @Test
    void rejectsShapeAndBoundsAndPermitsExactEdgesWithoutOverflow() throws Exception {
        for (String field : List.of("x", "y", "width", "height")) {
            var value = anchor().put(field, -1);
            assertThrows(Exception.class, () -> mapper.treeToValue(value, ReviewAnchor.class));
        }
        for (String field :
                List.of("documentWidth", "documentHeight", "viewportWidth", "viewportHeight")) {
            for (int number : List.of(0, field.startsWith("document") ? 1_000_001 : 16_385)) {
                var value = anchor().put(field, number);
                assertThrows(Exception.class, () -> mapper.treeToValue(value, ReviewAnchor.class));
            }
        }
        for (ObjectNode value :
                List.of(
                        anchor().put("space", "other"),
                        anchor().put("shape", "other"),
                        anchor().put("shape", "point"),
                        anchor().put("width", 0),
                        anchor().put("height", 0),
                        anchor().put("x", 90),
                        anchor().put("y", 190),
                        anchor().put("x", Integer.MAX_VALUE).put("width", Integer.MAX_VALUE)))
            assertThrows(Exception.class, () -> mapper.treeToValue(value, ReviewAnchor.class));
        assertDoesNotThrow(
                () -> mapper.treeToValue(anchor().put("x", 80).put("y", 170), ReviewAnchor.class));
        assertDoesNotThrow(
                () ->
                        mapper.treeToValue(
                                anchor().put("shape", "point")
                                        .put("x", 100)
                                        .put("y", 200)
                                        .put("width", 0)
                                        .put("height", 0),
                                ReviewAnchor.class));
    }
}
