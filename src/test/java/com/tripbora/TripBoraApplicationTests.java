package com.tripbora;

import com.tripbora.service.category.CountryCategorySeedTransactionService;
import com.tripbora.service.email.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        // Flyway 는 기동 중에 DB 에 바로 붙는다. 이 테스트는 실제 DB 없이 컨텍스트만 본다.
        "spring.flyway.enabled=false",
        "spring.mail.username=",
        "spring.mail.password=",
        "spring.security.oauth2.client.registration.google.client-id=test-google-client",
        "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
        "spring.security.oauth2.client.registration.kakao.client-id=test-kakao-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-kakao-secret",
        "spring.security.oauth2.client.registration.naver.client-id=test-naver-client",
        "spring.security.oauth2.client.registration.naver.client-secret=test-naver-secret"
})
class TripBoraApplicationTests {

    @Autowired private EmailService emailService;
    @MockitoBean private ClientRegistrationRepository clientRegistrationRepository;
    /*
      기준 데이터 적재는 실제 DB 를 요구한다. 이 테스트가 보는 것은 "메일 자격증명 없이도
      컨텍스트가 뜨는가" 이므로 저장 경계만 가짜로 바꾼다.
      적재 자체의 동작은 CountryCategoryLoaderTest 와
      CountryCategorySeedTransactionServiceTest 가 따로 확인한다.
    */
    @MockitoBean private CountryCategorySeedTransactionService countryCategorySeedService;

    @Test
    void contextLoadsWithoutMailCredentials() {
        org.assertj.core.api.Assertions.assertThat(emailService).isNotNull();
    }

}
