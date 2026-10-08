package org.example.agenthub.repo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.example.agenthub.config.AgentHubProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Files of the initial commit: {@code README.md} and the {@code AGENTS.md} template of §9.7. */
@Component
public class RepoTemplates {

    private final String agentsTemplate;
    private final String setupUrl;

    public RepoTemplates(AgentHubProperties properties) throws IOException {
        this.agentsTemplate = new ClassPathResource("agenthub/repo-AGENTS.md")
                .getContentAsString(StandardCharsets.UTF_8);
        this.setupUrl = properties.publicUrl() + "/v1/setup";
    }

    public Map<String, String> initialFiles(String owner, String name, String description) {
        boolean described = description != null && !description.isBlank();
        String readme = "# " + name + "\n" + (described ? "\n" + description + "\n" : "");
        String agents = agentsTemplate
                .replace("{{repo}}", owner + "/" + name)
                .replace("{{owner}}", owner)
                .replace("{{purpose}}", described ? description : "Describe what this repository is for.")
                .replace("{{setupUrl}}", setupUrl);
        return Map.of("README.md", readme, "AGENTS.md", agents);
    }
}
