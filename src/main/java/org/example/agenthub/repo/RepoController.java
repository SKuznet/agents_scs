package org.example.agenthub.repo;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.example.agenthub.auth.AgentPrincipal;
import org.example.agenthub.web.Paged;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/repos")
class RepoController {

    private final RepoService repos;

    RepoController(RepoService repos) {
        this.repos = repos;
    }

    @PostMapping
    ResponseEntity<RepoView> create(AgentPrincipal me, @Valid @RequestBody RepoRequests.Create request) {
        RepoService.Created result = repos.create(me.handle(), request);
        RepoView repo = result.repo();
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .location(URI.create("/v1/repos/" + repo.owner() + "/" + repo.name()))
                .body(repo);
    }

    @GetMapping
    Paged<RepoView> list(AgentPrincipal me, @RequestParam(required = false) String owner,
            @RequestParam(defaultValue = "false") boolean sharedWithMe, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return repos.list(me.handle(), owner, sharedWithMe, cursor, limit);
    }

    @GetMapping("/{owner}/{name}")
    RepoView get(AgentPrincipal me, @PathVariable String owner, @PathVariable String name) {
        return repos.get(me.handle(), owner, name);
    }

    @GetMapping("/{owner}/{name}/branches")
    List<RepoService.BranchView> branches(AgentPrincipal me, @PathVariable String owner, @PathVariable String name) {
        return repos.branches(me.handle(), owner, name);
    }

    @PatchMapping("/{owner}/{name}")
    RepoView update(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @Valid @RequestBody RepoRequests.Update request) {
        return repos.update(me.handle(), owner, name, request);
    }

    @DeleteMapping("/{owner}/{name}")
    ResponseEntity<Void> delete(AgentPrincipal me, @PathVariable String owner, @PathVariable String name) {
        repos.delete(me.handle(), owner, name);
        return ResponseEntity.noContent().build();
    }
}
