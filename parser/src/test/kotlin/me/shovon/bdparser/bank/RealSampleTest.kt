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
    fun `EBL foreign purchase keeps its currency and the BDT account balance`() {
        val t = parse("EBL", "Purchase txn USD3.99 from NETFLIX.COM SINGAP.Card 4520170001 on 04-Oct-26 03:52:08 AM BST.Your A/C 1230456 Balance BDT 43417.94. EBL Helpline 16230")!!
        assertEquals(EXPENSE, t.type)
        assertEquals(BigDecimal("3.99"), t.amount)
        assertEquals("USD", t.currency)
        assertEquals("NETFLIX.COM SINGAP", t.merchant)
        assertEquals("0456", t.accountLast4)
        assertEquals(BigDecimal("43417.94"), t.balance)
    }

    @Test
    fun `foreign-currency bank alert in an unknown shape is rejected rather than recorded as taka`() {
        assertNull(parse("EBL", "QR txn USD 5 through EBL Skybanking at SOME SHOP from Card 452017**0001 on 01-Oct-26 01:42:16 PM BST. EBL Helpline 16230"))
    }

    @Test
    fun `EBL ATM withdrawal, card-to-beneficiary payment and reversal parse`() {
        check("EBL", "Cash WD BDT2000 from EBL DHK CANT.KACHUKH. Card 452017**0001 on 13-Sep-23 11:15:58 AM BST.Your A/C 123**0456 Balance BDT 54321.00. EBL Helpline 16230",
            EXPENSE, "2000", "Cash withdrawal", "0456", "54321.00")
        check("EBL", "Payment of BDT 5000 has been credited to beneficiary using Card 452017**0001 on 05-Sep-26 08:10:11 PM. Thank You. EBL Helpline 16230",
            EXPENSE, "5000", "Card transfer", "0001", null)
        val r = parse("EBL", "EBL CARDS: Purchase txn USD9.99 from AMAZON PRIME PMTS Amzn.co reversed using Card 452017**0001. Ref num 123456789. EBL Helpline 16230")!!
        assertEquals(INCOME, r.type)
        assertEquals(BigDecimal("9.99"), r.amount)
        assertEquals("USD", r.currency)
        assertEquals("AMAZON PRIME PMTS Amzn.co", r.merchant)
        assertEquals(true, r.isReversal)
    }

    @Test
    fun `bKash shapes the keyword heuristics misread`() {
        check("bKash", "You have received Tk 5.00 from DM0000. Fee Tk 0.00. Balance Tk 4,593.00. TrxID AAA0000006 at 09/02/2023 20:42. Cashback on Special Offer Send Money",
            INCOME, "5.00", "Cashback", null, "4593.00")
        check("bKash", "Remittance fund withdrawal from Payoneer account is successful. Total amount Tk 5,473.02 TrxID AAA0000007 at 01/12/2025 14:36.  Remittance Cash Out charge only 7 Tk/thousand from ATM Details: https://bka.sh/ATMCO",
            INCOME, "5473.02", "Payoneer", null, null)
        check("bKash", "You have received remittance. Total: Tk 11,018.75 Govt. incentive: Tk 268.75 TrxID AAA0000008 at 18/07/2026 07:39.  Remittance Cash Out charge only 7 Tk/thousand from ATM Details: https://bka.sh/ATMCO",
            INCOME, "11018.75", "Remittance", null, null)
        check("bKash", "Mobile Recharge request has failed. Tk 20.00 returned to your bKash Account. Balance Tk 30.00. TrxID AAA0000009 at 01/01/2024 10:00",
            INCOME, "20.00", "Mobile Recharge", null, "30.00")
        check("bKash", "Apni bKash-e Tk 3.50 Interest peyechhen. Fee Tk 0.35. Balance Tk 1,234.56. TrxID AAA0000010 at 01/07/2024 10:00. Helpline 16247. Interest for Jan-Jun 2024",
            INCOME, "3.50", "Interest", null, "1234.56", fee = "0.35")
        check("bKash", "bKash to Bank of Tk 2,500.00 for VISA Debit Card is successful. Fee Tk 31.25. Balance Tk 72.73. TrxID AAA0000011 at 29/03/2024 21:39",
            EXPENSE, "2500.00", "VISA Debit Card", null, "72.73", fee = "31.25")
        check("bKash", "You have received deposit of Tk 200.00 from VISA Card. Fee Tk 0.00. Balance Tk 240.30. TrxID AAA0000012 at 18/04/2023 22:43",
            INCOME, "200.00", "VISA Card", null, "240.30")
    }

    @Test
    fun `notices that duplicate or announce another SMS are not transactions`() {
        listOf(
            "bKash" to "Payment of Tk 88.00 is being reserved for SOME MERCHANT-RM0000. Balance Tk 186.62. TrxID AAA0000013 at 24/02/2026 20:29",
            "bKash" to "Tk 1032.36 will be automatically deducted as Loan instalment today. If already repaid, please ignore this message.",
            "bKash" to "You have received Loan of Tk 7,000.00 from City Bank in your bKash Account. Your first repayment of TK 2408.85 is due on 10/07/2026.",
            "bKash" to "Your bKash Mobile Recharge request of Tk 17.00 for 01700000000 was successful. Use bKash App for convenience & offers! TCA",
            "bKash" to "Your Account Binding request for SOME MERCHANT is successful. You have authorized SOME MERCHANT to debit your account for future purchases.",
            "EBL" to "Your DESCO  prepaid meter no: 00000000 is successfully recharged with BDT 200, Energy Cost BDT 191.43, VAT BDT 9.52. Your Token Number is",
            "EBL" to "Your VISA DEBIT CARD has been sent to EBL SOME BRANCH. Pls collect it after 5 working days or ignore, if already collected.",
        ).forEach { (sender, body) ->
            val p = BankParserFactory.getParser(sender, body)!!
            assertEquals(true, p.isNotice(body), body)
            assertNull(p.parse(body, sender, 0L), body)
        }
    }

    @Test
    fun `Nagad, Tap and City Bank name their counterparties, balances and fees`() {
        check("NAGAD", "Payment to 'Software Shop Ltd' is Successful. Amount: Tk  2034.59 TxnID: 71AAAAAA Balance: Tk 65.41 30/03/2023 19:45",
            EXPENSE, "2034.59", "Software Shop Ltd", null, "65.41")
        check("NAGAD", "Add Money from Bank is Successful. From: Eastern Bank PLC. Amount: Tk 9200.0 TxnID: 75AAAAAA Balance: Tk 9205.59 15/03/2026 12:29",
            INCOME, "9200.0", "Eastern Bank PLC", null, "9205.59")
        check("NAGAD", "Cash In Received. Amount: Tk 2000.00 Uddokta: 01700000000 TxnID: 71BBBBBB Balance: 2657.34 01/05/2023 10:00",
            INCOME, "2000.00", null, null, "2657.34")
        check("tap", "Received Tk. 5500.00 from EBL. Fee Tk. 0.00 Your current balance is Tk 5530.50.",
            INCOME, "5500.00", "EBL", null, null)
        check("tap", "Cash Out Tk 5000.00 to 8801700000000. Fee Tk Paid TK. 73.50. Balance Tk 26.50. TxID: AAAAAAAAAAAAAA",
            EXPENSE, "5000.00", null, null, "26.50", fee = "73.50")
        check("CITYBANK", "08-Jan-2026\nCITYTOUCH TXN\nTk. 6,000 Withdrawal\nTk. 1,502 Balance\nA/C: 1234***5678\nNPSB Fee: Tk 10",
            EXPENSE, "6000", "Citytouch transfer", "5678", "1502", fee = "10")
        check("CITYBANK", "20-Dec-2025\nE-COMM/POS TXN\nTk. 355 Purchased\nTk. 12 Balance\nA/C: 1234***5678",
            EXPENSE, "355", "Card purchase", "5678", "12")
        val notice = "Dear customer, your card 4105xxxxxxxx0000 has been successfully added for Tap and Pay/ Online payment service. If you did not enroll, call 16234 immediately."
        assertEquals(true, BankParserFactory.getParser("CITYBANK", notice)!!.isNotice(notice))
    }

    @Test
    fun `SimpleDate rejects impossible dates`() {
        assertEquals(SimpleDate(2024, 2, 29), SimpleDate.ofOrNull(2024, 2, 29))
        assertNull(SimpleDate.ofOrNull(2025, 2, 29))
        assertNull(SimpleDate.ofOrNull(2025, 13, 1))
    }
}
