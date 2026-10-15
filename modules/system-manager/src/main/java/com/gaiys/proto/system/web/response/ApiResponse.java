package com.gaiys.proto.system.web.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.hswebframework.web.crud.web.ResponseMessage;

@JsonInclude(JsonInclude.Include.ALWAYS)
public class ApiResponse<T> extends ResponseMessage<T> {
    public boolean isSuccess() {
        return getStatus() >= 200 && getStatus() < 300;
    }
}
