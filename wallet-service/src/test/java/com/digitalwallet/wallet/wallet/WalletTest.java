package com.digitalwallet.wallet.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.digitalwallet.wallet.error.ApiException;

import org.junit.jupiter.api.Test;

class WalletTest {

    @Test
    void creditAndDebitAdjustBalance() {
        Wallet wallet = new Wallet(1L, "INR");
        wallet.credit(new BigDecimal("100.00"));
        wallet.debit(new BigDecimal("40.50"));
        assertThat(wallet.getBalance()).isEqualByComparingTo("59.50");
    }

    @Test
    void debitMoreThanBalanceIsRejectedAndBalanceUnchanged() {
        Wallet wallet = new Wallet(1L, "INR");
        wallet.credit(new BigDecimal("10.00"));

        assertThatThrownBy(() -> wallet.debit(new BigDecimal("10.01")))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "INSUFFICIENT_FUNDS");
        assertThat(wallet.getBalance()).isEqualByComparingTo("10.00");
    }

    @Test
    void nonPositiveAmountsAreRejected() {
        Wallet wallet = new Wallet(1L, "INR");
        assertThatThrownBy(() -> wallet.credit(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> wallet.debit(new BigDecimal("-1"))).isInstanceOf(IllegalArgumentException.class);
    }
}
