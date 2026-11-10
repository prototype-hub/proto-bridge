package com.gaiys.proto.prototypehub.service.validation;

import com.gaiys.proto.prototypehub.entity.PrototypeRevisionMaterialEntity;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.request.RevisionMaterialRequests;
import java.net.URI;
import java.util.stream.Stream;

/** JSON和multipart共用的来源信任边界；地址仅保存，不发起外部请求。 */
public record RevisionMaterialSource(String tool, String name, String url, String notes) {
    public static RevisionMaterialSource of(RevisionMaterialRequests.Source input) {
        if (input.getSourceUrl() != null
                && input.getSourceUrl().chars().anyMatch(Character::isISOControl)) throw invalid();
        var source =
                new RevisionMaterialSource(
                        text(input.getSourceTool(), 80), text(input.getSourceName(), 200),
                        text(input.getSourceUrl(), 2048), text(input.getSourceNotes(), 2000));
        if (source.url != null) {
            try {
                URI uri = URI.create(source.url);
                if (!("http".equalsIgnoreCase(uri.getScheme())
                                || "https".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null
                        || uri.getRawUserInfo() != null
                        || source.url.chars().anyMatch(Character::isISOControl)) throw invalid();
            } catch (IllegalArgumentException error) {
                throw invalid();
            }
        }
        return source;
    }

    public boolean recorded() {
        return Stream.of(tool, name, url, notes).anyMatch(value -> value != null);
    }

    public void apply(PrototypeRevisionMaterialEntity row) {
        row.setSourceTool(tool);
        row.setSourceName(name);
        row.setSourceUrl(url);
        row.setSourceNotes(notes);
    }

    private static String text(String value, int maximum) {
        if (value == null) return null;
        if (value.length() > maximum || value.indexOf('\0') >= 0) throw invalid();
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static PrototypeHubException invalid() {
        return PrototypeHubException.badRequest(
                "error.prototype-hub.material_invalid", "PROTOTYPE_HUB_MATERIAL_INVALID");
    }
}
