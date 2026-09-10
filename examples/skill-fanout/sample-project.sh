#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Greco
# SPDX-License-Identifier: Apache-2.0
#
# The small project the real-model demos review. A function rather than a checked-in directory,
# so demo-model.sh and demo-concurrent.sh scaffold exactly the same thing and `examples/` does
# not carry a fake project someone might mistake for part of the build.
#
# Sourced, never run:  scaffold_sample <workspace>
#
# Three defects, one per directory, each findable without reading the other two. That is what
# makes this a fan-out rather than three agents reading the same file three times.

scaffold_sample() {
  local workspace="$1"
  mkdir -p "$workspace/checkout/auth" "$workspace/checkout/errors" "$workspace/checkout/config"

  cat > "$workspace/checkout/README.md" <<'EOF'
# checkout

Takes an order, authenticates against the payments provider, charges the card, and returns a
result. Three areas: `auth/` (sessions and tokens), `errors/` (talking to the gateway), and
`config/` (timeouts and limits).
EOF

  # A refresh that fails leaves a stale token behind and says nothing.
  cat > "$workspace/checkout/auth/Session.java" <<'EOF'
package checkout.auth;

/** Holds the access token used for every call to the payments provider. */
final class Session {

    private String token;
    private Instant expiresAt;

    String token() {
        if (Instant.now().isAfter(expiresAt)) {
            refresh();
        }
        return token;
    }

    private void refresh() {
        try {
            var answer = http.post(REFRESH_URL, Map.of("grant_type", "refresh_token"));
            token = answer.get("access_token");
            expiresAt = Instant.now().plusSeconds(3600);
        } catch (IOException e) {
            // Refresh failed.
        }
    }
}
EOF

  # An outage at the gateway is reported to the customer as a declined card.
  cat > "$workspace/checkout/errors/Gateway.java" <<'EOF'
package checkout.errors;

/** Every call to the payment gateway goes through here. */
final class Gateway {

    PaymentResult charge(Order order) {
        try {
            return client.post("/charge", order);
        } catch (Exception e) {
            return PaymentResult.declined("card declined");
        }
    }

    PaymentResult refund(String chargeId) {
        try {
            return client.post("/refund/" + chargeId, null);
        } catch (Exception e) {
            return PaymentResult.declined("refund declined");
        }
    }
}
EOF

  # The comment says thirty seconds; the value is half of one.
  cat > "$workspace/checkout/config/Timeouts.java" <<'EOF'
package checkout.config;

/** Network settings for the payment gateway. */
final class Timeouts {

    /** How long to wait for the gateway to answer: thirty seconds. */
    static final Duration READ = Duration.ofMillis(500);

    /** How long to wait for the connection itself. */
    static final Duration CONNECT = Duration.ofSeconds(30);

    /** Retries on a failed charge. */
    static final int MAX_ATTEMPTS = 1;
}
EOF
}
