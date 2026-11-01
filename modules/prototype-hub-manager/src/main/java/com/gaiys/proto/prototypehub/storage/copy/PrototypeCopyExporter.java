package com.gaiys.proto.prototypehub.storage.copy;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gaiys.proto.prototypehub.exception.PrototypeHubException;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse;
import com.gaiys.proto.prototypehub.web.response.CopyInventoryResponse.Report;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 有界整页报表，仅在FileWork序列化；CSV公式保护不改变JSON保存的原文。 */
@Component
@RequiredArgsConstructor
public class PrototypeCopyExporter {
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    private final ObjectMapper mapper;
    private static final List<String> COLUMNS =
            List.of(
                    "recordType",
                    "projectId",
                    "versionId",
                    "revisionId",
                    "pageId",
                    "snapshotId",
                    "inventoryVersion",
                    "pagePath",
                    "manifestSha256",
                    "fileSha256",
                    "extractorVersion",
                    "extractionState",
                    "limitReason",
                    "staticComplete",
                    "dynamicTextGap",
                    "id",
                    "kind",
                    "text",
                    "businessTerm",
                    "note",
                    "requirementUrl",
                    "occurrenceCount",
                    "occurrences",
                    "configVersion",
                    "createdAt",
                    "updatedAt");

    public byte[] bytes(Report report, String format) throws IOException {
        var output = new LimitedOutput();
        if ("json".equals(format)) {
            try {
                mapper.writeValue(output, report);
            } catch (JsonMappingException failure) {
                // Jackson会包装OutputStream预算异常，恢复明确422，其他格式错误保持原异常。
                if (failure.getCause() instanceof PrototypeHubException business) throw business;
                throw failure;
            }
        } else if ("csv".equals(format)) {
            var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            writer.write('\ufeff');
            line(writer, COLUMNS);
            var context = context(report.snapshot());
            var first = new ArrayList<Object>();
            first.add("SNAPSHOT");
            first.addAll(context);
            first.addAll(Arrays.asList("", "", "", "", "", "", "", "", "", "", ""));
            line(writer, first);
            for (var entry : report.entries()) {
                var row = new ArrayList<Object>();
                row.add("ENTRY");
                row.addAll(context);
                row.addAll(
                        List.of(
                                entry.id(),
                                entry.kind(),
                                entry.text(),
                                entry.businessTerm(),
                                entry.note(),
                                entry.requirementUrl(),
                                entry.occurrenceCount(),
                                mapper.writeValueAsString(entry.occurrences()),
                                entry.configVersion(),
                                entry.createdAt(),
                                entry.updatedAt()));
                line(writer, row);
            }
            writer.flush();
        } else throw PrototypeCopyExtractor.error("FORMAT_INVALID", 400);
        return output.buffer.toByteArray();
    }

    private static List<Object> context(CopyInventoryResponse snapshot) {
        return Arrays.asList(
                snapshot.projectId(),
                snapshot.versionId(),
                snapshot.revisionId(),
                snapshot.pageId(),
                snapshot.snapshotId(),
                snapshot.configVersion(),
                snapshot.pagePath(),
                snapshot.manifestSha256(),
                snapshot.fileSha256(),
                snapshot.extractorVersion(),
                snapshot.extractionState(),
                snapshot.limitReason(),
                snapshot.staticComplete(),
                snapshot.dynamicTextGap());
    }

    private static void line(java.io.Writer writer, List<?> values) throws IOException {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) writer.write(',');
            writer.write('"');
            writer.write(cell(values.get(index)));
            writer.write('"');
        }
        writer.write("\r\n");
    }

    static String cell(Object raw) {
        String value = raw == null ? "" : raw.toString();
        boolean protect = !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0;
        for (int offset = 0; offset < value.length(); ) {
            int code = value.codePointAt(offset);
            offset += Character.charCount(code);
            if (Character.isWhitespace(code)
                    || Character.isSpaceChar(code)
                    || Character.isISOControl(code)
                    || code == 0xfeff) continue;
            protect |= code == '=' || code == '+' || code == '-' || code == '@';
            break;
        }
        return (protect ? "'" : "") + value.replace("\"", "\"\"");
    }

    private static final class LimitedOutput extends OutputStream {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public void write(int value) {
            if (buffer.size() == MAX_BYTES)
                throw PrototypeCopyExtractor.error("EXPORT_LIMIT_EXCEEDED", 422);
            buffer.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) {
            if (length > MAX_BYTES - buffer.size())
                throw PrototypeCopyExtractor.error("EXPORT_LIMIT_EXCEEDED", 422);
            buffer.write(value, offset, length);
        }
    }
}
