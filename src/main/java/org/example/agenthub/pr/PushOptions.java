package org.example.agenthub.pr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.example.agenthub.repo.NamePolicy;
import org.example.agenthub.web.ErrorCode;
import org.example.agenthub.web.HintedException;

/**
 * Push options of §7.3 ({@code git push -o key[=value]}). {@code draft} and {@code autoMerge} are {@code null}
 * when the push does not mention them; {@code applied} names what the push sets, for the agent's summary.
 */
public record PushOptions(String title, String description, String target, Boolean draft, Boolean autoMerge,
        List<String> reviewers, List<String> applied) {

    public static final PushOptions NONE = new PushOptions(null, null, null, null, null, null, List.of());

    static final int MAX_REVIEWERS = 20;

    static final int MAX_TITLE = 1000;
    private static final String KNOWN = "title=<text>, description=<text>, target=<branch>, draft, ready,"
            + " auto-merge, no-auto-merge, reviewer=<handle>[,<handle>...]";

    public static PushOptions parse(List<String> raw, int maxOptions, long maxValueBytes) {
        if (raw == null || raw.isEmpty()) {
            return NONE;
        }
        if (raw.size() > maxOptions) {
            throw invalid("at most " + maxOptions + " push options are accepted, got " + raw.size());
        }
        String title = null;
        String description = null;
        String target = null;
        Boolean draft = null;
        Boolean autoMerge = null;
        List<String> reviewers = null;
        List<String> applied = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String option : raw) {
            int eq = option.indexOf('=');
            String key = eq < 0 ? option : option.substring(0, eq);
            String value = eq < 0 ? null : option.substring(eq + 1);
            if (value != null && value.getBytes(StandardCharsets.UTF_8).length > maxValueBytes) {
                throw invalid("the value of '" + key + "' is longer than " + maxValueBytes + " bytes");
            }
            String slot = switch (key) {
                case "ready" -> "draft";
                case "no-auto-merge" -> "auto-merge";
                default -> key;
            };
            if (!seen.add(slot)) {
                throw invalid("option '" + key + "' conflicts with an earlier one");
            }
            switch (key) {
                case "title" -> {
                    title = required(key, value);
                    if (title.length() > MAX_TITLE) {
                        throw invalid("title is longer than " + MAX_TITLE + " characters");
                    }
                    applied.add("title");
                }
                case "description" -> {
                    description = required(key, value);
                    applied.add("description");
                }
                case "target" -> {
                    target = checkTarget(required(key, value));
                    applied.add("target=" + target);
                }
                case "draft", "ready" -> {
                    if (value != null) {
                        throw invalid("option '" + key + "' takes no value");
                    }
                    draft = key.equals("draft");
                    applied.add(key);
                }
                case "reviewer" -> {
                    reviewers = Arrays.stream(required(key, value).split(",")).map(String::strip)
                            .filter(handle -> !handle.isEmpty()).distinct().toList();
                    if (reviewers.isEmpty() || reviewers.size() > MAX_REVIEWERS) {
                        throw invalid("option 'reviewer' takes 1-" + MAX_REVIEWERS + " handles separated by ','");
                    }
                    for (String handle : reviewers) {
                        if (!NamePolicy.isValidHandle(handle)) {
                            throw invalid("'" + handle + "' is not an agent handle");
                        }
                    }
                    applied.add("reviewer=" + String.join(",", reviewers));
                }
                case "auto-merge", "no-auto-merge" -> {
                    if (value != null) {
                        throw invalid("option '" + key + "' takes no value");
                    }
                    autoMerge = key.equals("auto-merge");
                    applied.add(key);
                }
                default -> throw unknown(key);
            }
        }
        return new PushOptions(title, description, target, draft, autoMerge, reviewers, List.copyOf(applied));
    }

    /**
     * Options of {@code POST /pulls} (§10.3): flags as in {@code git push -o}, while title, description and target
     * come as fields of the request, so the description may span lines.
     */
    public static PushOptions forRequest(List<String> flags, String title, String description, String target,
            int maxOptions, long maxValueBytes) {
        PushOptions parsed;
        try {
            parsed = parse(flags, maxOptions, maxValueBytes);
        } catch (HintedException e) {
            throw e.code() == ErrorCode.UNKNOWN_PUSH_OPTION ? invalid(e.getMessage()) : e;
        }
        if (parsed.title() != null || parsed.description() != null || parsed.target() != null) {
            throw new HintedException(ErrorCode.VALIDATION_FAILED, "title, description and target are fields of the"
                    + " request, not options", "send {\"title\": ..., \"description\": ..., \"target\": ...}"
                    + " and keep 'options' for draft, ready, auto-merge, no-auto-merge and reviewer=<handles>");
        }
        List<String> applied = new ArrayList<>(parsed.applied());
        if (title != null) {
            if (title.isBlank() || title.length() > MAX_TITLE) {
                throw invalid("title must have 1-" + MAX_TITLE + " characters");
            }
            applied.add("title");
        }
        if (description != null) {
            if (description.length() > PullRequest.MAX_DESCRIPTION) {
                throw invalid("description is longer than " + PullRequest.MAX_DESCRIPTION + " characters");
            }
            applied.add("description");
        }
        if (target != null) {
            applied.add("target=" + checkTarget(target));
        }
        return new PushOptions(title, description, target, parsed.draft(), parsed.autoMerge(), parsed.reviewers(),
                List.copyOf(applied));
    }

    private static String checkTarget(String target) {
        String problem = NamePolicy.refNameProblem(target);
        if (problem != null || target.equals("pr") || target.startsWith("pr/")) {
            throw invalid("target '" + target + "' is not a branch name" + (problem == null ? "" : ": " + problem));
        }
        return target;
    }

    private static String required(String key, String value) {
        if (value == null || value.isEmpty()) {
            throw invalid("option '" + key + "' needs a value: -o " + key + "=...");
        }
        return value;
    }

    private static HintedException unknown(String key) {
        return new HintedException(ErrorCode.UNKNOWN_PUSH_OPTION, "unknown push option '" + key + "'",
                "supported options: " + KNOWN);
    }

    private static HintedException invalid(String detail) {
        return new HintedException(ErrorCode.VALIDATION_FAILED, detail, "supported options: " + KNOWN);
    }
}
