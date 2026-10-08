package org.example.agenthub.repo;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.example.agenthub.access.Role;
import org.example.agenthub.auth.AgentPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/repos/{owner}/{name}/grants")
class GrantController {

    private final GrantService grants;

    GrantController(GrantService grants) {
        this.grants = grants;
    }

    record GrantRequest(@NotNull Role role) {
    }

    @GetMapping
    List<GrantService.GrantView> list(AgentPrincipal me, @PathVariable String owner, @PathVariable String name) {
        return grants.list(me.handle(), owner, name);
    }

    @PutMapping("/{agent}")
    GrantService.GrantView put(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable String agent, @Valid @RequestBody GrantRequest request) {
        return grants.put(me.handle(), owner, name, agent, request.role());
    }

    @DeleteMapping("/{agent}")
    ResponseEntity<Void> revoke(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable String agent) {
        grants.revoke(me.handle(), owner, name, agent);
        return ResponseEntity.noContent().build();
    }
}
