package com.gaiys.proto.prototypehub.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/** 访客身份的保障等级；自述资料在任何等级下都不代表平台核验。 */
public enum ReviewVerificationLevel {
    email,
    unverified;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReviewVerificationLevel fromJson(JsonNode input) {
        if (input == null || !input.isTextual())
            throw new IllegalArgumentException("Invalid verification level");
        return ReviewVerificationLevel.valueOf(input.textValue());
    }
}
