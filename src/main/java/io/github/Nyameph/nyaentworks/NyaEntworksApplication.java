package io.github.Nyameph.nyaentworks;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("io.github.Nyameph.nyaentworks.**.mapper")
public class NyaEntworksApplication {

    public static void main(String[] args) {
        SpringApplication.run(NyaEntworksApplication.class, args);
    }

}
