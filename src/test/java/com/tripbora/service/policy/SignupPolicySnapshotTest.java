package com.tripbora.service.policy;

import com.tripbora.model.PolicyType;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignupPolicySnapshotTest {
    private final SignupPolicySet policies = SignupPolicyFixtures.activeSignupPolicies();

    @Test
    void requiresTheExactDisplayedSnapshotIncludingReadOnlyAndOptionalPolicies() {
        List<Long> all = policies.policies().stream().map(SignupPolicy::policyVersionId).toList();
        assertThat(policies.decide(SignupPolicyFixtures.requiredConsentIds(), all)).hasSize(3);
        for (Long omitted : all) {
            List<Long> incomplete = all.stream().filter(id -> !id.equals(omitted)).toList();
            assertThatThrownBy(() -> policies.decide(SignupPolicyFixtures.requiredConsentIds(), incomplete))
                    .isInstanceOf(PolicySnapshotException.class)
                    .hasFieldOrPropertyWithValue("messageCode", "signup.error.terms.changed");
        }
        assertThatThrownBy(() -> policies.decide(List.of(999L), all))
                .isInstanceOf(PolicySnapshotException.class);
    }

    @Test
    void absentOrMisconfiguredCorePolicyCannotAllowSignup() {
        for (PolicyType core : List.of(PolicyType.TERMS_OF_SERVICE, PolicyType.PRIVACY_COLLECTION)) {
            for (int invalid = 0; invalid < 3; invalid++) {
                java.util.ArrayList<SignupPolicy> rows = new java.util.ArrayList<>(policies.policies());
                rows.removeIf(p -> p.type() == core);
                if (invalid > 0) {
                    rows.add(SignupPolicyFixtures.policy(999L, core, invalid != 1, false));
                }
                SignupPolicySet broken = new SignupPolicySet(rows);
                assertThatThrownBy(() -> broken.decide(List.of(), List.of()))
                        .isInstanceOf(PolicySnapshotException.class)
                        .hasFieldOrPropertyWithValue("messageCode", "signup.error.terms.unavailable");
            }
        }
    }

    @Test
    void decliningGalleryAndMarketingStillAllowsSignup() {
        SignupPolicySet set = new SignupPolicySet(List.of(
                SignupPolicyFixtures.policy(101, PolicyType.TERMS_OF_SERVICE, true, true),
                SignupPolicyFixtures.policy(103, PolicyType.PRIVACY_COLLECTION, true, true),
                SignupPolicyFixtures.policy(104, PolicyType.MARKETING_EMAIL, true, false),
                SignupPolicyFixtures.policy(105, PolicyType.REVIEW_GALLERY, true, false)));
        assertThat(set.decide(List.of(101L, 103L), List.of(101L, 103L, 104L, 105L)))
                .filteredOn(d -> d.type() == PolicyType.REVIEW_GALLERY || d.type() == PolicyType.MARKETING_EMAIL)
                .allMatch(d -> !d.agreed());
    }
}
