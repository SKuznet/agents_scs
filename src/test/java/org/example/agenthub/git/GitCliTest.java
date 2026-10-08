package org.example.agenthub.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.example.agenthub.IntegrationTest;
import org.example.agenthub.TestJws;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real git client (§16): what an agent sees in its terminal. The git process is isolated from the host:
 * no system config, a temporary HOME, no prompts; authentication comes from the {@code /v1/setup} recipe.
 */
@Tag("git-cli")
@EnabledIf("gitAvailable")
class GitCliTest extends IntegrationTest {

    private static final Pattern BASH_EXPORT = Pattern.compile("(?m)^export (GIT_[A-Z0-9_]+)=\"?(.*?)\"?$");

    @TempDir
    Path temp;

    Path home;

    record Result(int exitCode, String output) {
    }

    static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @BeforeEach
    void isolatedHome() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
    }

    /** Environment from the bash block of {@code /v1/setup}, with the agent's token in place. */
    Map<String, String> setupEnvironment(String token) {
        String setup = http.getForObject("/v1/setup", String.class);
        Map<String, String> env = new LinkedHashMap<>();
        Matcher m = BASH_EXPORT.matcher(setup);
        while (m.find()) {
            env.put(m.group(1), m.group(2).replace("$AGENTHUB_TOKEN", token));
        }
        assertThat(env).containsKeys("GIT_CONFIG_COUNT", "GIT_CONFIG_KEY_0", "GIT_CONFIG_VALUE_0",
                "GIT_TERMINAL_PROMPT");
        return env;
    }

    Map<String, String> envFor(String agent) {
        return setupEnvironment(TestJws.forAgent(agent));
    }

    Result git(Path dir, Map<String, String> env, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(key -> key.toUpperCase().startsWith("GIT_"));
        environment.put("HOME", home.toString());
        environment.put("USERPROFILE", home.toString());
        environment.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GCM_INTERACTIVE", "never");
        environment.put("GIT_AUTHOR_NAME", "agent");
        environment.put("GIT_AUTHOR_EMAIL", "agent@example.org");
        environment.put("GIT_COMMITTER_NAME", "agent");
        environment.put("GIT_COMMITTER_EMAIL", "agent@example.org");
        environment.putAll(env);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("git %s timed out", command).isTrue();
        return new Result(process.exitValue(), output);
    }

    Path cloneAs(String agent, String owner, String repo) throws Exception {
        Result clone = git(temp, envFor(agent), "clone", gitUrl(owner, repo), agent);
        assertThat(clone.exitCode()).as(clone.output()).isZero();
        return temp.resolve(agent);
    }

    @Test
    void setupRecipeAuthenticatesGitWithoutStoringTheToken() throws Exception {
        String alice = unique("alice");
        createRepo(alice, "proj");

        Path clone = cloneAs(alice, alice, "proj");

        assertThat(clone.resolve("AGENTS.md")).exists();
        String config = Files.readString(clone.resolve(".git/config"));
        assertThat(config).doesNotContain("Bearer").doesNotContain("Authorization");
        assertThat(git(clone, Map.of(), "remote", "-v").output()).doesNotContain("Bearer");
    }

    @Test
    void forbiddenPushShowsTheServerExplanationAsRemoteLines() throws Exception {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "READ");
        Path clone = cloneAs(bob, alice, "proj");
        git(clone, Map.of(), "commit", "--allow-empty", "-m", "try");

        Result push = git(clone, envFor(bob), "push", "origin", "HEAD:refs/heads/feature");

        assertThat(push.exitCode()).isNotZero();
        assertThat(push.output())
                .contains("remote: insufficient_role: pushing to " + alice + "/proj needs PROPOSE")
                .contains("remote: hint: ask the owner '" + alice + "'")
                .contains("403");
    }

    @Test
    void rejectedTokenIsExplainedInsteadOfPromptingForCredentials() throws Exception {
        String alice = unique("alice");
        createRepo(alice, "proj");

        Result lsRemote = git(temp, setupEnvironment("not-a-jws"), "ls-remote", gitUrl(alice, "proj"));

        assertThat(lsRemote.exitCode()).isNotZero();
        assertThat(lsRemote.output()).contains("remote: invalid_token: ").contains("remote: hint: ");
    }

    @Test
    void canonicalPushFromDetachedHeadOpensAPullRequestAndAmendUpdatesIt() throws Exception {
        String alice = unique("alice");
        String bob = unique("bob");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "PROPOSE");
        Path clone = cloneAs(bob, alice, "proj");
        git(clone, Map.of(), "checkout", "--detach");
        Files.writeString(clone.resolve("fix.txt"), "fix\n");
        git(clone, Map.of(), "add", "fix.txt");
        git(clone, Map.of(), "commit", "-m", "Fix parser");

        Result created = git(clone, envFor(bob), "push", "origin", "HEAD:refs/heads/pr/fix-parser");

        assertThat(created.exitCode()).as(created.output()).isZero();
        assertThat(created.output())
                .contains("remote: [agenthub] Created PR #1 -> main (rev 1)")
                .contains("remote:   Status:  GET /v1/repos/" + alice + "/proj/pulls/1")
                .contains("* [new branch]");

        Files.writeString(clone.resolve("fix.txt"), "better fix\n");
        git(clone, Map.of(), "commit", "-a", "--amend", "-m", "Fix parser properly");
        Result updated = git(clone, envFor(bob), "push", "origin", "HEAD:refs/heads/pr/fix-parser");

        assertThat(updated.exitCode()).as(updated.output()).isZero();
        assertThat(updated.output()).contains("remote: [agenthub] Updated PR #1 -> main (rev 2)")
                .doesNotContain("rejected");
    }

    @Test
    void pushOptionsTravelOverHttp() throws Exception {
        String alice = unique("alice");
        createRepo(alice, "proj");
        Path clone = cloneAs(alice, alice, "proj");
        git(clone, Map.of(), "commit", "--allow-empty", "-m", "subject from the commit");
        Files.writeString(clone.resolve("x.txt"), "x\n");
        git(clone, Map.of(), "add", "x.txt");
        git(clone, Map.of(), "commit", "-m", "add x");

        Result push = git(clone, envFor(alice), "push", "-o", "title=Title from option", "-o", "draft", "origin",
                "HEAD:refs/heads/pr/opts");

        assertThat(push.exitCode()).as(push.output()).isZero();
        assertThat(push.output()).contains("Created PR #1").contains("Draft:");
        Map<?, ?> pr = call(org.springframework.http.HttpMethod.GET, "/v1/repos/" + alice + "/proj/pulls/1", alice,
                null, Map.class).getBody();
        assertThat(pr.get("title")).isEqualTo("Title from option");
        assertThat(pr.get("draft")).isEqualTo(true);
    }

    @Test
    void autoMergeAndReviewerOptionsTravelOverHttp() throws Exception {
        String alice = unique("alice");
        String bob = unique("bob");
        String carol = unique("carol");
        createRepo(alice, "proj");
        grant(alice, "proj", bob, "PROPOSE");
        grant(alice, "proj", carol, "WRITE");
        Path writer = cloneAs(carol, alice, "proj");
        git(writer, envFor(carol), "push", "origin", "HEAD:refs/heads/dev");
        Path clone = cloneAs(bob, alice, "proj");
        Files.writeString(clone.resolve("x.txt"), "x\n");
        git(clone, Map.of(), "add", "x.txt");
        git(clone, Map.of(), "commit", "-m", "add x");

        Result push = git(clone, envFor(bob), "push", "-o", "target=dev", "-o", "auto-merge", "-o",
                "reviewer=" + carol, "origin", "HEAD:refs/heads/pr/x");

        assertThat(push.exitCode()).as(push.output()).isZero();
        assertThat(push.output()).contains("remote:   Auto-merge: waiting for 1 approval from a WRITE member");
        Map<?, ?> pr = call(org.springframework.http.HttpMethod.GET, "/v1/repos/" + alice + "/proj/pulls/1", bob,
                null, Map.class).getBody();
        assertThat(pr.get("autoMerge")).isEqualTo(true);
        assertThat(pr.get("reviewers")).isEqualTo(java.util.List.of(carol));
    }

    @Test
    void pushToProtectedBranchIsRejectedWithPullRequestHint() throws Exception {
        String alice = unique("alice");
        String carol = unique("carol");
        createRepo(alice, "proj");
        grant(alice, "proj", carol, "WRITE");
        Path clone = cloneAs(carol, alice, "proj");
        git(clone, Map.of(), "commit", "--allow-empty", "-m", "direct");
        String sha = git(clone, Map.of(), "rev-parse", "HEAD").output().trim();

        Result push = git(clone, envFor(carol), "push", "origin", "HEAD:refs/heads/main");

        assertThat(push.exitCode()).isNotZero();
        assertThat(push.output())
                .contains("! [remote rejected] HEAD -> main (protected_branch)")
                .contains("remote: [agenthub] refs/heads/main: protected_branch")
                .contains("remote: [agenthub]   hint: propose the change as a pull request: git push origin " + sha
                        + ":refs/heads/pr/" + carol + "-" + sha.substring(0, 7));

        Result unprotected = git(clone, envFor(carol), "push", "origin", "HEAD:refs/heads/feature");
        assertThat(unprotected.exitCode()).as(unprotected.output()).isZero();
    }
}
