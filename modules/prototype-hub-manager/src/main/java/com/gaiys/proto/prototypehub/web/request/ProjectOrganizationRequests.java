package com.gaiys.proto.prototypehub.web.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** Exact organization inputs. Identity, timestamps and relation ownership are server-owned. */
public final class ProjectOrganizationRequests {
    private ProjectOrganizationRequests() {}

    public abstract static class Exact {
        @JsonAnySetter
        public void unknown(String field, Object value) {
            throw new IllegalArgumentException("Unknown project organization field");
        }
    }

    /**
     * Matches JS Unicode whitespace, including nonbreaking spaces and BOM, without case folding.
     */
    public static String normalize(String value) {
        if (value == null) return null;
        int start = 0, end = value.length();
        while (start < end && whitespace(value.codePointAt(start)))
            start += Character.charCount(value.codePointAt(start));
        while (end > start && whitespace(value.codePointBefore(end)))
            end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }

    private static boolean whitespace(int code) {
        return Character.isWhitespace(code) || Character.isSpaceChar(code) || code == 0xFEFF;
    }

    @Getter
    @Setter
    public static class Mutation extends Exact {
        @NotNull
        @Min(0)
        @Max(9007199254740991L)
        @JsonDeserialize(using = PageDeliveryRequests.LongValue.class)
        private Long expectedVersion;

        @NotBlank
        @Pattern(regexp = "[!-~]{1,128}")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String clientRequestId;
    }

    @Getter
    @Setter
    public static class Organization extends Mutation {
        @Size(max = 64)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String groupName;

        @NotNull
        @Size(max = 10)
        @JsonDeserialize(contentUsing = ApprovalRequests.Text.class)
        private List<@NotNull @Size(max = 32) @Pattern(regexp = "[^\\x00]*") String> tags;

        @NotNull
        @JsonDeserialize(using = DemoScriptRequests.BooleanValue.class)
        private Boolean archived;

        public void setGroupName(String value) {
            groupName = normalize(value);
        }

        public void setTags(List<String> values) {
            tags =
                    values == null
                            ? null
                            : values.stream().map(ProjectOrganizationRequests::normalize).toList();
        }

        public List<String> normalizedTags() {
            return tags.stream().filter(value -> !value.isEmpty()).distinct().sorted().toList();
        }
    }

    @Getter
    @Setter
    public static class Favorite extends Mutation {
        @NotNull
        @JsonDeserialize(using = DemoScriptRequests.BooleanValue.class)
        private Boolean favorite;
    }

    @Getter
    @Setter
    public static class Options extends Exact {
        @NotNull
        @Pattern(regexp = "group|tag")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String kind;

        @Size(max = 64)
        @Pattern(regexp = "[^\\x00]*")
        @JsonDeserialize(using = ApprovalRequests.Text.class)
        private String keyword;

        @Min(0)
        @Max(10000)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageIndex = 0;

        @Min(1)
        @Max(100)
        @JsonDeserialize(using = PageDeliveryRequests.IntegerValue.class)
        private int pageSize = 20;

        public void setKeyword(String value) {
            keyword = normalize(value);
        }
    }
}
