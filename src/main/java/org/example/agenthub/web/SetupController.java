package org.example.agenthub.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.example.agenthub.config.AgentHubProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The static, unauthenticated cheat sheet of §9.10, served as {@code /v1/setup} and {@code /AGENTS.md}. */
@RestController
class SetupController {

    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final String cheatSheet;

    SetupController(AgentHubProperties properties) throws IOException {
        this.cheatSheet = new ClassPathResource("agenthub/setup.md").getContentAsString(StandardCharsets.UTF_8)
                .replace("{{publicUrl}}", properties.publicUrl())
                .replace("{{errorCodes}}", errorTable());
    }

    @GetMapping({"/v1/setup", "/AGENTS.md"})
    ResponseEntity<String> setup() {
        return ResponseEntity.ok().contentType(MARKDOWN).body(cheatSheet);
    }

    private static String errorTable() {
        return Arrays.stream(ErrorCode.values())
                .map(code -> "| `" + code.code() + "` | " + httpOf(code) + " | " + code.title() + " |")
                .collect(Collectors.joining("\n"));
    }

    private static String httpOf(ErrorCode code) {
        return switch (code) {
            case UNKNOWN_PUSH_OPTION, ATOMIC_MIXED_PUSH, TOO_MANY_COMMANDS, NOT_A_COMMIT -> "git only";
            default -> String.valueOf(code.status().value());
        };
    }
}
