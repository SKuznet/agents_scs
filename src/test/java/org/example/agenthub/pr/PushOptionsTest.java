package org.example.agenthub.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;
import org.junit.jupiter.api.Test;

class PushOptionsTest {

    static PushOptions parse(String... options) {
        return PushOptions.parse(List.of(options), 20, 4096);
    }

    static void assertRefused(ErrorCode code, String... options) {
        assertThatThrownBy(() -> parse(options)).isInstanceOfSatisfying(HintedException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test
    void nullAndEmptyMeanNoOptions() {
        assertThat(PushOptions.parse(null, 20, 4096).applied()).isEmpty();
        assertThat(PushOptions.parse(Collections.emptyList(), 20, 4096)).isEqualTo(PushOptions.NONE);
    }

    @Test
    void knownOptionsAreParsedAtTheFirstEqualsSign() {
        PushOptions options = parse("title=Fix parser: a=b", "description=Handles x=y", "target=release/1.0", "draft");

        assertThat(options.title()).isEqualTo("Fix parser: a=b");
        assertThat(options.description()).isEqualTo("Handles x=y");
        assertThat(options.target()).isEqualTo("release/1.0");
        assertThat(options.draft()).isTrue();
        assertThat(options.applied()).containsExactly("title", "description", "target=release/1.0", "draft");
    }

    @Test
    void readyClearsDraft() {
        assertThat(parse("ready").draft()).isFalse();
        assertThat(parse("title=x").draft()).isNull();
    }

    @Test
    void unknownOptionsAreRefusedWithTheListOfKnownOnes() {
        assertThatThrownBy(() -> parse("colour=red")).isInstanceOfSatisfying(HintedException.class, e -> {
            assertThat(e.code()).isEqualTo(ErrorCode.UNKNOWN_PUSH_OPTION);
            assertThat(e.getMessage()).contains("colour");
            assertThat(e.hint()).contains("title=").contains("target=").contains("draft").contains("ready");
        });
    }

    @Test
    void reviewersAreAListOfHandles() {
        PushOptions options = parse("reviewer=carol, erin,carol");

        assertThat(options.reviewers()).containsExactly("carol", "erin");
        assertThat(options.applied()).containsExactly("reviewer=carol,erin");
        assertThat(parse("title=x").reviewers()).isNull();
        assertRefused(ErrorCode.VALIDATION_FAILED, "reviewer=Carol");
        assertRefused(ErrorCode.VALIDATION_FAILED, "reviewer=,");
    }

    @Test
    void autoMergeCanBeSwitchedOnOrOff() {
        assertThat(parse("auto-merge").autoMerge()).isTrue();
        assertThat(parse("no-auto-merge").autoMerge()).isFalse();
        assertThat(parse("title=x").autoMerge()).isNull();
        assertThat(parse("auto-merge").applied()).containsExactly("auto-merge");
    }

    @Test
    void malformedOptionsAreValidationFailures() {
        assertRefused(ErrorCode.VALIDATION_FAILED, "title");
        assertRefused(ErrorCode.VALIDATION_FAILED, "title=");
        assertRefused(ErrorCode.VALIDATION_FAILED, "draft=yes");
        assertRefused(ErrorCode.VALIDATION_FAILED, "draft", "ready");
        assertRefused(ErrorCode.VALIDATION_FAILED, "auto-merge", "no-auto-merge");
        assertRefused(ErrorCode.VALIDATION_FAILED, "auto-merge=yes");
        assertRefused(ErrorCode.VALIDATION_FAILED, "title=a", "title=b");
        assertRefused(ErrorCode.VALIDATION_FAILED, "target=Main");
        assertRefused(ErrorCode.VALIDATION_FAILED, "target=pr/x");
        assertRefused(ErrorCode.VALIDATION_FAILED, "title=" + "x".repeat(1001));
    }

    @Test
    void countAndSizeAreLimited() {
        assertThatThrownBy(() -> PushOptions.parse(Collections.nCopies(3, "draft"), 2, 4096))
                .isInstanceOfSatisfying(HintedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> PushOptions.parse(List.of("description=" + "x".repeat(11)), 20, 10))
                .isInstanceOfSatisfying(HintedException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
