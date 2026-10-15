package com.gaiys.proto.system.web.errors;

import com.gaiys.proto.system.web.response.ApiResponse;
import java.util.Locale;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.hswebframework.web.authorization.exception.AuthenticationException;
import org.hswebframework.web.authorization.exception.UnAuthorizedException;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.I18nSupportException;
import org.hswebframework.web.i18n.LocaleUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Exceptions;

/** 将应用及官方框架异常转换为统一响应，保留已有状态、错误码和国际化消息。 */
public final class ApiErrors {
    private ApiErrors() {}

    public static ApiResponse<Void> response(Throwable failure, Locale locale) {
        Throwable error = Exceptions.unwrap(failure);
        int status = 500;
        String code = "INTERNAL_ERROR";
        String key = "error.internal";
        if (error instanceof UnAuthorizedException || error instanceof AuthenticationException) {
            status = 401;
            code = "UNAUTHORIZED";
            key = "error.unauthorized";
        } else if (error instanceof AccessDenyException) {
            status = 403;
            code = "FORBIDDEN";
            key = "error.forbidden";
        } else if (error instanceof BusinessException business) {
            status = business.getStatus();
            // 官方 CRUD 的无栈 NotFoundException 可省略 code 与消息键，不能把 null 交给消息源或审计。
            code = StringUtils.hasText(business.getCode()) ? business.getCode() : "HTTP_" + status;
            key =
                    StringUtils.hasText(business.getI18nCode())
                            ? business.getI18nCode()
                            : "error.http." + status;
        } else if (error instanceof ResponseStatusException http) {
            status = http.getStatusCode().value();
            code = "HTTP_" + status;
            key = "error.http." + status;
        } else if (error instanceof DuplicateKeyException
                || error instanceof org.hswebframework.ezorm.rdb.exception.DuplicateKeyException) {
            status = 409;
            code = "CONFLICT";
            key = "error.conflict";
        } else if (error instanceof WebExchangeBindException
                || error instanceof IllegalArgumentException
                || error instanceof jakarta.validation.ValidationException
                || error instanceof org.hswebframework.web.exception.ValidationException) {
            status = 400;
            code = "BAD_REQUEST";
            key = "error.bad_request";
        }
        ApiResponse<Void> response = new ApiResponse<>();
        response.setStatus(status);
        response.setCode(code);
        response.setMessage(
                error instanceof I18nSupportException i18n
                                && StringUtils.hasText(i18n.getI18nCode())
                                && status < 500
                                && status != 401
                        ? i18n.getLocalizedMessage(locale)
                        : LocaleUtils.resolveMessage(key, locale, key));
        return response;
    }
}
