package org.example.agenthub.repo;

import org.example.agenthub.auth.AgentPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Branches with '/' do not fit a path segment, so the branch is a query parameter (App. A.10). */
@RestController
@RequestMapping("/v1/repos/{owner}/{name}/protection")
class BranchProtectionController {

    private final BranchProtectionService protection;

    BranchProtectionController(BranchProtectionService protection) {
        this.protection = protection;
    }

    @GetMapping
    BranchProtectionService.ProtectionView get(AgentPrincipal me, @PathVariable String owner,
            @PathVariable String name, @RequestParam String branch) {
        return protection.get(me.handle(), owner, name, branch);
    }

    @PutMapping
    BranchProtectionService.ProtectionView put(AgentPrincipal me, @PathVariable String owner,
            @PathVariable String name, @RequestParam String branch,
            @RequestBody BranchProtectionService.Settings settings) {
        return protection.replace(me.handle(), owner, name, branch, settings);
    }

    @DeleteMapping
    ResponseEntity<Void> delete(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @RequestParam String branch) {
        protection.remove(me.handle(), owner, name, branch);
        return ResponseEntity.noContent().build();
    }
}
