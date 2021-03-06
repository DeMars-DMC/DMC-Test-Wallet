package io.demars.stellarwallet.interfaces

import org.apache.commons.lang.builder.HashCodeBuilder
import org.stellar.sdk.responses.AccountResponse

interface StellarAccount {
    fun getAccountId() : String
    fun getInflationDestination() : String?
    fun getSequenceNumber() : Long
    fun getSubEntryCount() : Int?

    fun getAccountResponse(): AccountResponse

    /**
     * This is was inspired by {@link <a href=]//github.com/Block-Equity/stellar-ios-wallet/blob/ee2414061e309e2b97c4883541ca1cd335994487/StellarHub/Objects/StellarAccount.swift.L253">stellar ios wallet"</a>}
     */
    fun basicHashCode(): Int {
        return HashCodeBuilder(17, 37)
                .append(getAccountId())
                .append(getInflationDestination())
                .append(getSubEntryCount())
                .toHashCode()
    }

    fun basicEquals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as StellarAccount

        /**
         * for now let's use the hashCode.
         */
        if (basicHashCode() != other.basicHashCode()) return false

        return true
    }
}
