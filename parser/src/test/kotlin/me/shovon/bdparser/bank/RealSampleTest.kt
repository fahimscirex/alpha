/*
 * This file is part of the alpha budget app's port of bd-sms-parsers,
 * licensed under the GNU AGPL v3. See LICENSE for the full text.
 */
package me.shovon.bdparser.bank

import me.shovon.bdparser.ParsedTransaction
import me.shovon.bdparser.SimpleDate
import me.shovon.bdparser.TransactionType
import me.shovon.bdparser.TransactionType.EXPENSE
import me.shovon.bdparser.TransactionType.INCOME
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Real EBL and bKash SMS (account, card and phone numbers and TrxIDs replaced) that the
 * upstream library either rejected or misread.
 */
class RealSampleTest {

    private fun parse(sender: String, body: String): ParsedTransaction? =
        BankParserFactory.getParser(sender, body)?.parse(body, sender, 0L)

    private fun check(
        sender: String, body: String, type: TransactionType, amount: String,
        merchant: String?, account: String?, balance: String?, fee: String? = null,
    ) {
        val t = parse(sender, body) ?: error("not parsed: $body")
        assertEquals(type, t.type, body)
        assertEquals(BigDecimal(amount), t.amount, body)
        assertEquals(merchant, t.merchant, body)
        assertEquals(account, t.accountLast4, body)
        assertEquals(balance?.let(::BigDecimal), t.balance, body)
        assertEquals(fee?.let(::BigDecimal), t.fee, body)
        assertEquals("BDT", t.currency)
    }

    @Test
    fun `EBL account alerts parse for any reason, with balance and masked account tail`() {
        check("EBL", "AC 123***456 is debited with BDT 400 as EBL Skybanking MFS Transfer-bKash on 01-OCT-26 03:48:17 PM Balance is BDT 44099.59 Thanks. EBL Helpline 16230",
            EXPENSE, "400", "bKash", "456", "44099.59")
        check("EBL", "AC 123***456 is credited with BDT 276.48 as IC INTEREST LIQUIDATION on 01-DEC-25 01:28:17 AM Balance is BDT 237.46 Thanks. EBL Helpline 16230",
            INCOME, "276.48", "IC INTEREST LIQUIDATION", "456", "237.46")
        check("EBL", "AC 123***456 is debited with BDT 41.47 as WITHOLDING SOURCE TAX ON CASA ACCOUNTS on 01-DEC-25 01:28:17 AM Balance is BDT 237.46 Thanks. EBL Helpline 16230",
            EXPENSE, "41.47", "WITHOLDING SOURCE TAX ON CASA ACCOUNTS", "456", "237.46")
    }

    @Test
    fun `EBL debit card purchase and QR payment parse`() {
        // Debit card: the balance is the linked account's, and the account wins attribution.
        check("EBL", "Purchase txn BDT 100 from btcl.gov.bd 024831.Card 4520170001 on 29-Sep-26 11:55:40 AM BST.Your A/C 1230456 Balance BDT 45119.59. EBL Helpline 16230",
            EXPENSE, "100", "btcl.gov.bd", "0456", "45119.59")
        check("EBL", "QR txn BDT 20 through EBL Skybanking at BANGLA QR PAYMENT  from Card 452017**0001 on 01-Oct-26 01:42:16 PM BST. EBL Helpline 16230",
            EXPENSE, "20", "BANGLA QR PAYMENT", "0001", null)
    }

    @Test
    fun `EBL debit card purchase names the card linked to the account`() {
        val t = parse("EBL", "Purchase txn BDT 100 from btcl.gov.bd 024831.Card 4520170001 on 29-Sep-26 11:55:40 AM BST.Your A/C 1230456 Balance BDT 45119.59. EBL Helpline 16230")!!
        assertEquals("0456", t.accountLast4)
        assertEquals("0001", t.cardLast4)
        assertNull(parse("EBL", "QR txn BDT 20 through EBL Skybanking at BANGLA QR PAYMENT  from Card 452017**0001 on 01-Oct-26 01:42:16 PM BST. EBL Helpline 16230")!!.cardLast4)
    }

    @Test
    fun `bKash alerts parse with non-zero fees surfaced`() {
        check("bKash", "You have received deposit from iBanking of Tk 1,000.00 from Eastern Bank PLC. Internet Banking. Fee Tk 0.00. Balance Tk 1,186.62. TrxID AAA0000001 at 25/02/2026 16:07",
            INCOME, "1000.00", "Eastern Bank PLC", null, "1186.62")
        check("bKash", "Payment of Tk 2,491.50 to ACI LOGISTICS LIMITED-RM60674 is successful. Balance Tk 2,016.93. TrxID AAA0000002 at 08/02/2026 18:03",
            EXPENSE, "2491.50", "ACI LOGISTICS LIMITED", null, "2016.93")
        check("bKash", "ATM Cash Out Tk 4,000.00 successful. Fee Tk 28.00. Balance Tk 4,508.43. TrxID AAA0000003 at 08/02/2026 17:57",
            EXPENSE, "4000.00", null, null, "4508.43", fee = "28.00")
        check("bKash", "You have received Tk 500.00 from 01700000000.Ref Loan on 3 feb. Fee Tk 0.00. Balance Tk 7,761.43. TrxID AAA0000004 at 05/02/2026 16:25",
            INCOME, "500.00", null, null, "7761.43")
        check("bKash", "Send Money Tk 500.00 to 01800000000 successful. Ref 1. Fee Tk 5.00. Balance Tk 745.28. TrxID AAA0000005 at 03/02/2026 17:18",
            EXPENSE, "500.00", null, null, "745.28", fee = "5.00")
    }

    @Test
    fun `foreign-currency bank alert is rejected rather than recorded as taka`() {
        assertNull(parse("EBL", "Purchase txn USD 12.99 from NETFLIX.COM.Card 452017**0001 on 01-Oct-26 01:42:16 PM BST. EBL Helpline 16230"))
    }

    @Test
    fun `SimpleDate rejects impossible dates`() {
        assertEquals(SimpleDate(2024, 2, 29), SimpleDate.ofOrNull(2024, 2, 29))
        assertNull(SimpleDate.ofOrNull(2025, 2, 29))
        assertNull(SimpleDate.ofOrNull(2025, 13, 1))
    }
}
