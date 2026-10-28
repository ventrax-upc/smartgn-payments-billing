package com.smartgn.billing.application;

import java.util.UUID;

public interface IdentityPort {
    record Account(UUID accountId, String email, String role, String accountKind, String status) {}

    Account resolve(String token);
}
