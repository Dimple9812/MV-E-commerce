package com.marketflow.identity.application;

import com.marketflow.platform.web.ApiException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PasswordPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"short1", "onlyletterslong", "1234567890123", ""})
    void rejectsWeakPasswords(String pw) {
        assertThatThrownBy(() -> PasswordPolicy.validate(pw)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CorrectHorse9", "abcdefghij1", "pässwörd12345"})
    void acceptsReasonablePasswords(String pw) {
        assertThatCode(() -> PasswordPolicy.validate(pw)).doesNotThrowAnyException();
    }

    @org.junit.jupiter.api.Test
    void rejectsOver72Bytes() {
        assertThatThrownBy(() -> PasswordPolicy.validate("a1".repeat(37))).isInstanceOf(ApiException.class);
    }
}
