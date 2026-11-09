package com.tripbora.service.policy;

import com.tripbora.model.PolicyConsentAction;
import com.tripbora.model.PolicyConsentSource;
import com.tripbora.model.PolicyType;
import com.tripbora.model.UserPolicyConsent;
import com.tripbora.repository.user.UserPolicyConsentMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PolicyConsentRecorderActionTest {
    @ParameterizedTest
    @EnumSource(value = PolicyConsentSource.class, names = {"SIGNUP", "SOCIAL_SIGNUP"})
    void signupRecordsUncheckedOptionalPolicyAsDeclineAndCheckedPolicyAsAgree(PolicyConsentSource source) {
        UserPolicyConsentMapper mapper = mock(UserPolicyConsentMapper.class);
        when(mapper.insertConsent(any())).thenReturn(1);
        new PolicyConsentRecorder(mapper).record(7L, List.of(
                new PolicyConsentDecision(1L, PolicyType.TERMS_OF_SERVICE, true),
                new PolicyConsentDecision(2L, PolicyType.REVIEW_GALLERY, false)), source, "ko");
        ArgumentCaptor<UserPolicyConsent> captor = ArgumentCaptor.forClass(UserPolicyConsent.class);
        verify(mapper, times(2)).insertConsent(captor.capture());
        assertThat(captor.getAllValues()).extracting(UserPolicyConsent::getDecisionAction)
                .containsExactly(PolicyConsentAction.AGREE, PolicyConsentAction.DECLINE);
        assertThat(captor.getAllValues()).extracting(UserPolicyConsent::isAgreed).containsExactly(true, false);
        assertThat(captor.getAllValues()).extracting(UserPolicyConsent::getConsentSource).containsOnly(source);
        verify(mapper, never()).revokeGalleryImages(any());
    }
}
