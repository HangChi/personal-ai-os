package com.personalos.common.config;
import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
class ProductionSecurityValidatorTest {
    final String strong = "fixture-password-of-24bytes";
    @Test void localModeDoesNotRequireProductionSecrets() {
        assertThatCode(() -> new ProductionSecurityValidator(false,false,false,false,null,null)).doesNotThrowAnyException();
    }
    @ParameterizedTest @CsvSource({"false,false,false,AUTH_ENABLED","true,false,false,csrf-enabled","true,true,false,SESSION_COOKIE_SECURE"})
    void productionRejectsMissingSecurityControls(boolean auth,boolean csrf,boolean secure,String message) {
        assertThatThrownBy(() -> new ProductionSecurityValidator(true,auth,csrf,secure,strong,strong)).hasMessageContaining(message);
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","password","changeme","personalos","123456789012345"})
    void weakAuthenticationFailsClosed(String password) {
        assertThatThrownBy(() -> new ProductionSecurityValidator(true,true,true,true,password,strong)).hasMessageContaining("16-72");
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={" ","personalos","postgres"})
    void defaultDatabasePasswordsAreRejected(String password) {
        assertThatThrownBy(() -> new ProductionSecurityValidator(true,true,true,true,strong,password)).hasMessageContaining("POSTGRES_PASSWORD");
    }
    @Test void passwordBoundsUseUtf8Bytes() {
        for(String value:new String[]{"x".repeat(16),"x".repeat(72),"😀".repeat(4),"😀".repeat(18)})
            assertThatCode(() -> new ProductionSecurityValidator(true,true,true,true,value,strong)).doesNotThrowAnyException();
        for(String value:new String[]{"x".repeat(73),"😀".repeat(19)})
            assertThatThrownBy(() -> new ProductionSecurityValidator(true,true,true,true,value,strong)).hasMessageContaining("16-72");
    }
}
