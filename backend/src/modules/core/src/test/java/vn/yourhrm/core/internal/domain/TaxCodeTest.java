package vn.yourhrm.core.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import vn.yourhrm.shared.common.error.ApiException;

/** MST format (BR-CORE-003, VN-03): 10 digits or NNNNNNNNNN-NNN. */
class TaxCodeTest {

    @ParameterizedTest
    @ValueSource(strings = {"0101234567", "0101234567-001", " 0101234567 "})
    void acceptsTenDigitsOrDependentUnitSuffix(String raw) {
        assertThat(TaxCode.parse(raw).value()).isEqualTo(raw.strip());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "010123456", "01012345678", "0101234567001", "0101234567-01", "0101234567-0001",
            "010123456A", "0101 234567"})
    void rejectsAnythingElse(String raw) {
        assertThatThrownBy(() -> TaxCode.parse(raw))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("invalid-tax-code");
    }

    @Test
    void recognisesDependentUnits() {
        assertThat(TaxCode.parse("0101234567-001").isDependentUnit()).isTrue();
        assertThat(TaxCode.parse("0101234567").isDependentUnit()).isFalse();
    }
}
