package com.brainserve.appointment.integration.slack;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SlackConfigurationTest {
    @Test void blankMalformedOrSecretUrlsDisableSlack() {
        for(String value:new String[]{"","bad","http://app.test","https://user:secret@app.test/","https://app.test/?token=secret","https://app.test/#secret","https://app.test/api"})
            assertThat(new SlackConfiguration(value).configured()).as(value).isFalse();
    }
    @Test void configuredLinkUsesNormalAuthorizedApplicationHomepage() {
        var config=new SlackConfiguration("https://brainserve.test");
        assertThat(config.configured()).isTrue();assertThat(config.arrivalLink()).isEqualTo("https://brainserve.test/");
    }
}
