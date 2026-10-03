package pl.hackyeah.controllayer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ControlLayerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ControlLayerApplication.class, args);
	}

}
