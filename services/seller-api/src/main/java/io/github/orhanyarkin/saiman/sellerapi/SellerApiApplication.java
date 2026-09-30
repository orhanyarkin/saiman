package io.github.orhanyarkin.saiman.sellerapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SellerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SellerApiApplication.class, args);
    }
}
