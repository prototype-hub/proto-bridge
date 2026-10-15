package com.gaiys.proto.system.configuration.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Public presentation fields only; never bind or return the application environment. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "proto.front")
public class FrontendProperties {
    private String title = "Proto Bridge";
    private String logo = "";
    private String ico = "";
    private String background = "";
    private String headerTheme = "light";
    private boolean showRecordNumber;
    private String recordNumber = "";
    private String policeRecordNumber = "";
    private String copyright = "";
}
