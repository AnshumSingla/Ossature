package com.ossature.interviewer.security;

import com.ossature.interviewer.config.AppProperties;
import com.ossature.interviewer.service.Emails;
import java.util.List;
import org.springframework.stereotype.Component;

/** Single source of truth for "is this email the project owner" (PRD 9 review workflow). */
@Component
public class OwnerPolicy {

    private final AppProperties props;

    public OwnerPolicy(AppProperties props) {
        this.props = props;
    }

    public boolean isOwner(String email) {
        List<String> owners = props.auth().ownerEmails();
        if (email == null || owners == null) {
            return false;
        }
        String normalized = Emails.normalize(email);
        return owners.stream().map(Emails::normalize).anyMatch(normalized::equals);
    }
}
