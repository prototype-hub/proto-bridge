package com.gaiys.proto.logging.document;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gaiys.proto.logging.query.LogQuery;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Carries canonical fields shared by local log queries and Elasticsearch storage.
 *
 * <p>Producers create one document per event; {@link LogStore} assigns identity and masks text
 * before caching or enqueueing it. Read-only JSON aliases preserve the management UI contract
 * without changing the stored field names. This mutable document does not own persistence or
 * subscriptions.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LogDocument {
    private String id;

    @JsonProperty("@timestamp")
    private String timestamp;

    private String kind;
    private long createTime;
    private String server, level, message, name, threadName, exceptionStack;
    private String traceId, actorId, action, resourceType, resourceId, result;
    private long duration;
    private String httpMethod, url, describe, exception;
    private String ip, target, method, username;

    // Lombok treats getUsername/getUserName as conflicting names; persist the display
    // name explicitly so an ES JSON roundtrip preserves it separately from the login name.
    @JsonProperty("userName")
    private String userName;

    private Map<String, List<String>> httpHeaders, parameters;
    private long requestTime, responseTime;
    private int status;

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getUserId() {
        return actorId;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getClassName() {
        return name;
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public Map<String, String> getContext() {
        Map<String, String> context = new LinkedHashMap<>();
        if (server != null) context.put("server", server);
        if (username != null) context.put("username", username);
        if (userName != null) context.put("userName", userName);
        return context.isEmpty() ? null : context;
    }

    public LogDocument identified() {
        if (id == null) id = UUID.randomUUID().toString();
        if (createTime == 0) createTime = System.currentTimeMillis();
        timestamp = Instant.ofEpochMilli(createTime).toString();
        return this;
    }

    public String field(String name) {
        return switch (LogQuery.canonicalField(name)) {
            case "id" -> id;
            case "kind" -> kind;
            case "server" -> server;
            case "level" -> level;
            case "message" -> message;
            case "name" -> this.name;
            case "threadName" -> threadName;
            case "traceId" -> traceId;
            case "actorId" -> actorId;
            case "action" -> action;
            case "resourceType" -> resourceType;
            case "resourceId" -> resourceId;
            case "result" -> result;
            case "httpMethod" -> httpMethod;
            case "ip" -> ip;
            case "target" -> target;
            case "method" -> method;
            case "username" -> username;
            case "userName" -> userName;
            case "url" -> url;
            case "describe" -> describe;
            case "createTime" -> Long.toString(createTime);
            case "requestTime" -> Long.toString(requestTime);
            case "responseTime" -> Long.toString(responseTime);
            case "duration" -> Long.toString(duration);
            case "status" -> Integer.toString(status);
            default -> throw new IllegalArgumentException("validation.log_query_invalid");
        };
    }
}
