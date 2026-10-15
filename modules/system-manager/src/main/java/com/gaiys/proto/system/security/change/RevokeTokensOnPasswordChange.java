package com.gaiys.proto.system.security.change;

import lombok.AllArgsConstructor;
import org.hswebframework.web.authorization.token.UserTokenManager;
import org.hswebframework.web.system.authorization.api.event.UserModifiedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@AllArgsConstructor
public class RevokeTokensOnPasswordChange {
    private final UserTokenManager tokens;

    @EventListener
    public void changed(UserModifiedEvent event) {
        if (event.isPasswordModified())
            event.async(tokens.signOutByUserId(event.getUserEntity().getId()));
    }
}
