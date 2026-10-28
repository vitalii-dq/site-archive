package org.archive.sitearchive;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SiteArchiveApplication {

    public static void main(String[] args) {
        SpringApplication.run(SiteArchiveApplication.class, args);
    }
}
