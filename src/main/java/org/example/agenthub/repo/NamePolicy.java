package org.example.agenthub.repo;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;

/** Name rules of §4.4 (handles), §6.1 (repositories) and §6.3 (branches, tags, {@code pr/<name>}). */
public final class NamePolicy {

    public static final int MAX_REF_NAME_LENGTH = 100;

    private static final Pattern HANDLE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,38}");
    private static final Pattern REPO_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern REF_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*");
    private static final Set<String> RESERVED_HANDLES =
            Set.of("agenthub", "admin", "api", "git", "setup", "noreply", "system", "ghost");
    private static final Set<String> WINDOWS_DEVICES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3",
            "com4", "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7",
            "lpt8", "lpt9");

    private NamePolicy() {
    }

    public static boolean isValidHandle(String handle) {
        return handle != null && HANDLE.matcher(handle).matches();
    }

    public static boolean isReservedHandle(String handle) {
        return RESERVED_HANDLES.contains(handle);
    }

    public static boolean isValidRepoName(String name) {
        return name != null && REPO_NAME.matcher(name).matches();
    }

    /**
     * Why a branch or tag name (the part after {@code refs/heads/} or {@code refs/tags/}) is refused, or
     * {@code null} when it is fine. D/F conflicts depend on existing refs and are checked by the caller.
     */
    public static String refNameProblem(String name) {
        if (name == null || name.isEmpty()) {
            return "the name is empty";
        }
        if (name.length() > MAX_REF_NAME_LENGTH) {
            return "the name is longer than " + MAX_REF_NAME_LENGTH + " characters";
        }
        if (!REF_NAME.matcher(name).matches()) {
            return "use lowercase letters, digits, '.', '_' and '-' in '/'-separated parts, each starting with a"
                    + " letter or digit";
        }
        if (name.contains("..")) {
            return "the name contains '..'";
        }
        if (name.equals("head")) {
            return "'head' is reserved";
        }
        for (String part : name.split("/")) {
            if (part.endsWith(".") || part.endsWith(".lock")) {
                return "a part of the name ends with '.' or '.lock'";
            }
            // Windows treats these as devices even with an extension (nul.txt)
            String device = part.split("\\.", 2)[0].toLowerCase(Locale.ROOT);
            if (WINDOWS_DEVICES.contains(device)) {
                return "'" + part + "' is a reserved device name on Windows";
            }
        }
        if (!Repository.isValidRefName(Constants.R_HEADS + name)) {
            return "git does not accept this name";
        }
        return null;
    }
}
