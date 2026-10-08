package org.example.agenthub.repo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamePolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"bob", "a", "0day", "team_x-1", "a23456789012345678901234567890123456789"})
    void validHandles(String handle) {
        assertThat(NamePolicy.isValidHandle(handle)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bob", "-bob", "_bob", "bob.smith", "bob/x",
            "a234567890123456789012345678901234567890"})
    void invalidHandles(String handle) {
        assertThat(NamePolicy.isValidHandle(handle)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"parser", "a", "0", "my_repo-2",
            "a123456789012345678901234567890123456789012345678901234567890123"})
    void validRepositoryNames(String name) {
        assertThat(NamePolicy.isValidRepoName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Parser", "-x", "_x", "x.git", "a/b", "a b", "a~1",
            "a1234567890123456789012345678901234567890123456789012345678901234"})
    void invalidRepositoryNames(String name) {
        assertThat(NamePolicy.isValidRepoName(name)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "dev", "v1.0", "feature/x", "fix-parser_2", "release/2026.10/rc1", "a.b.c",
            "headless", "a/head"})
    void validRefNames(String name) {
        assertThat(NamePolicy.refNameProblem(name)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Main", "-x", ".x", "_x", "x/.y", "a..b", "x.", "x.lock", "x.lock/y", "a//b", "a/",
            "/a", "a b", "a~1", "a^1", "a:b", "a?", "a*", "a[1]", "a\\b", "a@{1}", "ü", "head", "con", "nul", "aux",
            "prn", "com1", "lpt9", "feature/con", "nul.txt", "COM1"})
    void invalidRefNames(String name) {
        assertThat(NamePolicy.refNameProblem(name)).isNotBlank();
    }

    @Test
    void refNamesAreAtMostOneHundredCharacters() {
        assertThat(NamePolicy.refNameProblem("a".repeat(100))).isNull();
        assertThat(NamePolicy.refNameProblem("a".repeat(101))).contains("100");
    }

    @ParameterizedTest
    @ValueSource(strings = {"agenthub", "admin", "api", "git", "setup", "noreply", "system", "ghost"})
    void reservedHandles(String handle) {
        assertThat(NamePolicy.isReservedHandle(handle)).isTrue();
        assertThat(NamePolicy.isValidHandle(handle)).isTrue();
    }
}
