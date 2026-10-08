package org.example.agenthub.events;

import org.example.agenthub.auth.AgentPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
class InboxController {

    private final InboxService inbox;
    private final LongPollRegistry polls;

    InboxController(InboxService inbox, LongPollRegistry polls) {
        this.inbox = inbox;
        this.polls = polls;
    }

    @GetMapping("/v1/inbox")
    InboxService.Inbox inbox(AgentPrincipal me) {
        return inbox.of(me.handle());
    }

    /** §9.5: answers once {@code inboxVersion} differs from {@code since}; without {@code since} at once. */
    @GetMapping(value = "/v1/inbox", params = "wait")
    DeferredResult<InboxService.Inbox> await(AgentPrincipal me, @RequestParam int wait,
            @RequestParam(required = false) String since) {
        String agent = me.handle();
        if (since == null) {
            DeferredResult<InboxService.Inbox> now = new DeferredResult<>();
            now.setResult(inbox.of(agent).with(WaitResult.SATISFIED));
            return now;
        }
        return polls.await(agent, null, wait, () -> {
            InboxService.Inbox now = inbox.of(agent);
            return now.inboxVersion().equals(since) ? null : now.with(WaitResult.SATISFIED);
        }, () -> inbox.of(agent).with(WaitResult.TIMEOUT));
    }
}
