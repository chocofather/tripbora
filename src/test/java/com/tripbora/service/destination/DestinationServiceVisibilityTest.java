package com.tripbora.service.destination;

import com.tripbora.repository.destination.DestinationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DestinationServiceVisibilityTest {

    @Mock private DestinationMapper destinationMapper;
    @InjectMocks private DestinationService destinationService;

    @Test
    void ordinaryUserCannotCallTheServiceEvenOutsideTheHttpEndpoint() {
        DestinationService securedService = securedService();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("user", null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            assertThatThrownBy(() -> securedService.setPublicVisible(10L, false))
                    .isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(destinationMapper);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void anonymousCannotCallTheServiceDirectly() {
        DestinationService securedService = securedService();
        SecurityContextHolder.clearContext();
        try {
            assertThatThrownBy(() -> securedService.setPublicVisible(10L, false))
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
            verifyNoInteractions(destinationMapper);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private DestinationService securedService() {
        ProxyFactory factory = new ProxyFactory(destinationService);
        factory.setProxyTargetClass(true);
        factory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        return (DestinationService) factory.getProxy();
    }

    @Test
    void hidingUpdatesOnlyVisibilityWithoutDeletingOrRewritingTheDestination() {
        when(destinationMapper.updateVisibility(10L, false)).thenReturn(1);

        assertThat(destinationService.setPublicVisible(10L, false)).isFalse();

        verify(destinationMapper).updateVisibility(10L, false);
        verifyNoMoreInteractions(destinationMapper);
    }

    @Test
    void showingUpdatesOnlyVisibility() {
        when(destinationMapper.updateVisibility(10L, true)).thenReturn(1);

        assertThat(destinationService.setPublicVisible(10L, true)).isTrue();

        verify(destinationMapper).updateVisibility(10L, true);
        verifyNoMoreInteractions(destinationMapper);
    }

    @Test
    void unchangedStateIsSuccessfulWhenDriverReportsOnlyChangedRows() {
        when(destinationMapper.updateVisibility(10L, false)).thenReturn(0);
        var hidden = new com.tripbora.model.Destination();
        hidden.setPublicVisible(false);
        when(destinationMapper.findById(10L)).thenReturn(hidden);
        assertThat(destinationService.setPublicVisible(10L, false)).isFalse();
        verify(destinationMapper).updateVisibility(10L, false);
        verify(destinationMapper).findById(10L);
        verifyNoMoreInteractions(destinationMapper);
    }

    @Test
    void missingDestinationThrowsNotFoundAndDoesNotStartOtherWrites() {
        when(destinationMapper.updateVisibility(10L, false)).thenReturn(0);

        assertThatThrownBy(() -> destinationService.setPublicVisible(10L, false))
                .isInstanceOf(DestinationNotFoundException.class);

        verify(destinationMapper).updateVisibility(10L, false);
        verify(destinationMapper).findById(10L);
        verifyNoMoreInteractions(destinationMapper);
    }

    @Test
    void persistenceFailurePropagatesInsteadOfReportingSuccess() {
        when(destinationMapper.updateVisibility(10L, false))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));

        assertThatThrownBy(() -> destinationService.setPublicVisible(10L, false))
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(destinationMapper).updateVisibility(10L, false);
        verifyNoMoreInteractions(destinationMapper);
    }
}
