package com.commit451.gitlab.event;

import com.commit451.gitlab.model.api.Issue;

/**
 * Oh no! An Issue!
 */
public class IssueCreatedEvent {
    public final Issue mIssue;

    public IssueCreatedEvent(Issue issue) {
        this.mIssue = issue;
    }
}
