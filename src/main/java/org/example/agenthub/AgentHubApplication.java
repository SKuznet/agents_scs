package org.example.agenthub;

import java.util.Arrays;

import org.example.agenthub.auth.TokenIssuerCli;
import org.example.agenthub.config.JGitEnvironment;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AgentHubApplication {

    public static void main(String[] args) {
        if (args.length > 0 && TokenIssuerCli.COMMAND.equals(args[0])) {
            System.exit(TokenIssuerCli.run(Arrays.copyOfRange(args, 1, args.length), System.getenv(), System.out,
                    System.err));
        }
        JGitEnvironment.install();
        SpringApplication.run(AgentHubApplication.class, args);
    }
}
