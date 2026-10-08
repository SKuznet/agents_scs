package org.example.agenthub.pr;

import org.example.agenthub.access.Role;

/** Text written by an agent, always handed out as data next to who wrote it (§9.8), never inside a hint. */
public record AgentText(String text, String author, Role authorRole) {
}
