package org.example.agenthub.content;

import jakarta.servlet.http.HttpServletRequest;

import org.example.agenthub.auth.AgentPrincipal;
import org.example.agenthub.pr.DiffService;
import org.example.agenthub.pr.Provenance;
import org.example.agenthub.web.Paged;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The content API of §10.5 and pull requests from files (§10.3 {@code POST /pulls}). */
@RestController
@RequestMapping("/v1/repos/{owner}/{name}")
class ContentController {

    private final ContentService contents;
    private final CommitService commits;

    ContentController(ContentService contents, CommitService commits) {
        this.contents = contents;
        this.commits = commits;
    }

    @GetMapping("/contents/{*path}")
    ResponseEntity<?> contents(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable String path, @RequestParam(required = false) String ref,
            @RequestParam(defaultValue = "false") boolean raw) {
        String relative = strip(path);
        if (raw) {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(contents.raw(me.handle(), owner, name, relative, ref));
        }
        return ResponseEntity.ok(contents.contents(me.handle(), owner, name, relative, ref));
    }

    @GetMapping("/commits")
    Paged<DiffService.CommitView> history(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @RequestParam(required = false) String ref, @RequestParam(required = false) String path,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        return contents.commits(me.handle(), owner, name, ref, path == null ? null : strip(path), cursor, limit);
    }

    @GetMapping("/commits/{sha}")
    ContentService.CommitDetails commit(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @PathVariable String sha, @RequestParam(required = false) Integer maxLines) {
        return contents.commit(me.handle(), owner, name, sha, maxLines);
    }

    @GetMapping("/compare")
    ContentService.Comparison compare(AgentPrincipal me, @PathVariable String owner, @PathVariable String name,
            @RequestParam String base, @RequestParam String head, @RequestParam(required = false) Integer maxLines) {
        return contents.compare(me.handle(), owner, name, base, head, maxLines);
    }

    @PostMapping("/commits")
    ResponseEntity<CommitService.Committed> commit(AgentPrincipal me, HttpServletRequest request,
            @PathVariable String owner, @PathVariable String name, @RequestBody CommitService.CommitRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(commits.commit(provenance(me, request), owner, name, body));
    }

    @PostMapping("/pulls")
    ResponseEntity<CommitService.Proposed> propose(AgentPrincipal me, HttpServletRequest request,
            @PathVariable String owner, @PathVariable String name, @RequestBody CommitService.ProposeRequest body) {
        CommitService.Proposed proposed = commits.propose(provenance(me, request), owner, name, body);
        return ResponseEntity.status(proposed.created() ? HttpStatus.CREATED : HttpStatus.OK).body(proposed);
    }

    private static Provenance provenance(AgentPrincipal me, HttpServletRequest request) {
        return Provenance.of(me, request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }

    private static String strip(String path) {
        String relative = path.startsWith("/") ? path.substring(1) : path;
        return relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
    }
}
