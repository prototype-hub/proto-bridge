package com.gaiys.proto.system.web.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.gaiys.proto.system.web.response.ApiResponse;
import java.util.Locale;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.exception.NotFoundException;
import org.hswebframework.web.i18n.LocaleUtils;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ApiErrorsTest {
    @Test
    void nativeStacklessNotFoundWithoutMetadataKeepsItsStatusAndHasACompleteEnvelope() {
        NotFoundException failure = new NotFoundException.NoStackTrace(null);
        assertNull(failure.getCode());
        assertNull(failure.getI18nCode());

        ApiResponse<Void> response = ApiErrors.response(failure, Locale.ENGLISH);

        assertEquals(404, response.getStatus());
        assertEquals("HTTP_404", response.getCode());
        assertEquals(
                LocaleUtils.resolveMessage("error.http.404", Locale.ENGLISH, "error.http.404"),
                response.getMessage());
        assertFalse(response.getMessage().isBlank());
        assertNull(response.getResult());
        assertFalse(response.isSuccess());
    }

    @Test
    void responseStatusExceptionKeepsItsStatusAndExistingHttpErrorContract() {
        ApiResponse<Void> response =
                ApiErrors.response(
                        new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED), Locale.ENGLISH);

        assertEquals(405, response.getStatus());
        assertEquals("HTTP_405", response.getCode());
        assertEquals(
                LocaleUtils.resolveMessage("error.http.405", Locale.ENGLISH, "error.http.405"),
                response.getMessage());
        assertFalse(response.getMessage().isBlank());
    }

    @Test
    void businessExceptionsKeepTheirExistingCodeAndLocalizedMessage() {
        BusinessException failure =
                new BusinessException("validation.management_invalid", "MANAGEMENT_INVALID", 400);
        for (Locale locale : new Locale[] {Locale.ENGLISH, Locale.CHINESE}) {
            ApiResponse<Void> response = ApiErrors.response(failure, locale);

            assertEquals(400, response.getStatus());
            assertEquals("MANAGEMENT_INVALID", response.getCode());
            assertEquals(failure.getLocalizedMessage(locale), response.getMessage());
            assertFalse(response.getMessage().isBlank());
        }
    }
}
