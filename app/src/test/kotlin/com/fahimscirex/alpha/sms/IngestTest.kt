package com.fahimscirex.alpha.sms

import com.fahimscirex.alpha.data.Account
import com.fahimscirex.alpha.data.Category
import com.fahimscirex.alpha.data.CategoryRule
import com.fahimscirex.alpha.data.CategoryTotal
import com.fahimscirex.alpha.data.DEFAULT_CATEGORIES
import com.fahimscirex.alpha.data.MoneyDao
import com.fahimscirex.alpha.data.Txn
import com.fahimscirex.alpha.data.TxnRow
import com.fahimscirex.alpha.data.TxnSide
import com.fahimscirex.alpha.data.UnparsedSms
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Real EBL and bKash SMS (numbers replaced) through Ingest against an in-memory MoneyDao. */
class IngestTest {

    private val min = 60_000L

    @Test
    fun `EBL to bKash transfer is linked and excluded from spending`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 1000 as EBL Skybanking MFS Transfer-bKash on 25-FEB-26 04:07:10 PM Balance is BDT 24912.52 Thanks. EBL Helpline 16230", 1_000 * min)
        Ingest.sms(dao, "bKash", "You have received deposit from iBanking of Tk 1,000.00 from Eastern Bank PLC. Internet Banking. Fee Tk 0.00. Balance Tk 1,186.62. TrxID AAA0000001 at 25/02/2026 16:07", 1_001 * min)
        // A same-amount bKash payment minutes later names no bank, so it must stay unlinked.
        Ingest.sms(dao, "bKash", "Payment of Tk 1,000.00 to SOME SHOP is successful. Balance Tk 186.62. TrxID AAA0000002 at 25/02/2026 16:10", 1_003 * min)

        val (out, inn, pay) = dao.txns
        assertEquals(inn.id, out.transferOf)
        assertEquals(out.id, inn.transferOf)
        assertNull(pay.transferOf)
        assertEquals(100_000L, dao.spent())
    }

    @Test
    fun `debit card is merged into its account and later card SMS land there`() = runBlocking {
        val dao = FakeDao()
        // Card-only SMS first: creates a separate card account.
        Ingest.sms(dao, "EBL", "QR txn BDT 20 through EBL Skybanking at BANGLA QR PAYMENT  from Card 452017**0001 on 01-Oct-26 01:42:16 PM BST. EBL Helpline 16230", 1 * min)
        // Names card and account together: merges the card into the account.
        Ingest.sms(dao, "EBL", "Purchase txn BDT 100 from btcl.gov.bd 024831.Card 4520170001 on 29-Sep-26 11:55:40 AM BST.Your A/C 1230456 Balance BDT 45119.59. EBL Helpline 16230", 2 * min)
        // Masked form of the same account, then another card-only SMS.
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 400 as EBL Skybanking MFS Transfer-bKash on 01-OCT-26 03:48:17 PM Balance is BDT 44099.59 Thanks. EBL Helpline 16230", 3 * min)
        Ingest.sms(dao, "EBL", "QR txn BDT 30 through EBL Skybanking at BANGLA QR PAYMENT  from Card 452017**0001 on 01-Oct-26 05:42:16 PM BST. EBL Helpline 16230", 4 * min)

        val visible = dao.accounts.filter { it.mergedInto == null }
        assertEquals(1, visible.size)
        assertEquals(setOf(visible[0].id), dao.txns.map { it.accountId }.toSet())
        assertEquals(4_409_959L, visible[0].balance)
    }

    @Test
    fun `USD card purchase stays on the BDT account with its taka cost from the balance drop`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 400 as EBL Skybanking MFS Transfer-bKash on 01-OCT-26 03:48:17 PM Balance is BDT 43900.00 Thanks. EBL Helpline 16230", 1 * min)
        Ingest.sms(dao, "EBL", "Purchase txn USD3.99 from NETFLIX.COM SINGAP.Card 4520170001 on 04-Oct-26 03:52:08 AM BST.Your A/C 1230456 Balance BDT 43417.94. EBL Helpline 16230", 2 * min)

        assertEquals(1, dao.accounts.count { it.mergedInto == null })
        val netflix = dao.txns.last()
        assertEquals("USD", netflix.currency)
        assertEquals(-399L, netflix.amount)
        assertEquals(-48_206L, netflix.bdtAmount)
        assertEquals(4_341_794L, dao.accounts.first { it.mergedInto == null }.balance)
        assertEquals(40_000L + 48_206L, dao.spent())
    }

    @Test
    fun `card reversal cancels its purchase and zero-amount checks are skipped`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "EBL", "Purchase txn BDT 0 from GOOGLE *TEMPORARY .Card 452017**0001 on 21-Jun-23 11:16:25 AM BST.Your A/C 123**0456 Balance BDT 5248.77. EBL Helpline 16230", 1 * min)
        Ingest.sms(dao, "EBL", "Purchase txn BDT 80 from GOOGLE *TEMPORARY .Card 452017**0001 on 07-Nov-23 01:46:24 AM BST.Your A/C 123**0456 Balance BDT 5168.77. EBL Helpline 16230", 2 * min)
        Ingest.sms(dao, "EBL", "EBL CARDS: Purchase txn BDT80 from GOOGLE *TEMPORARY HOLD g. reversed using Card 452017**0001. Ref num 145901843. EBL Helpline 16230", 3 * min)

        assertEquals(2, dao.txns.size)
        val (purchase, reversal) = dao.txns
        assertEquals(reversal.id, purchase.transferOf)
        assertEquals(purchase.accountId, reversal.accountId)
        assertEquals(0L, dao.spent())
    }

    @Test
    fun `EBL card top-up of bKash links with the bKash card deposit`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "EBL", "Purchase txn BDT 200 from BKASH LIMITED 01 B.Card 452017**0001 on 18-Apr-23 10:43:01 PM BST.Your A/C 123**0456 Balance BDT 5000.00. EBL Helpline 16230", 1 * min)
        Ingest.sms(dao, "bKash", "You have received deposit of Tk 200.00 from VISA Card. Fee Tk 0.00. Balance Tk 240.30. TrxID AAA0000012 at 18/04/2023 22:43", 2 * min)
        assertEquals(dao.txns[1].id, dao.txns[0].transferOf)
        assertEquals(0L, dao.spent())
    }

    @Test
    fun `older EBL Account Transfer links with a bKash iBanking deposit from Eastern Bank`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 6000 as EBL Account Transfer on 27-JUN-23 06:58:01 PM Balance is BDT 1000.00 Thanks. EBL Helpline 16230", 1 * min)
        Ingest.sms(dao, "bKash", "You have received deposit from iBanking of Tk 6,000.00 from Eastern Bank Limited Internet Banking. Fee Tk 0.00. Balance Tk 6,123.97. TrxID AAA0000014 at 27/06/2023 18:58", 2 * min)
        assertEquals(dao.txns[1].id, dao.txns[0].transferOf)
    }

    @Test
    fun `EBL to Tap transfer links through the EBL alias`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 5500 as EBL Skybanking MFS Transfer-Tap on 17-SEP-26 10:54:57 AM Balance is BDT 372.68 Thanks. EBL Helpline 16230", 1 * min)
        Ingest.sms(dao, "tap", "Received Tk. 5500.00 from EBL. Fee Tk. 0.00 Your current balance is Tk 5530.50.", 2 * min)
        assertEquals(dao.txns[1].id, dao.txns[0].transferOf)
    }

    @Test
    fun `transfers naming neither side link only when both are plain movements minutes apart`() = runBlocking {
        val dao = FakeDao()
        // EBL NPSB transfer to own City Bank account, 44 seconds apart.
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 3000 as NPSB FUND TRANSFER on 19-MAR-26 10:12:31 AM Balance is BDT 1000.00 Thanks. EBL Helpline 16230", 0L)
        Ingest.sms(dao, "CITYBANK", "19-Mar-2026\nTk. 3,000 Deposit\nTk. 3,003 Balance\nA/C: 1234***5678", 44_000L)
        assertEquals(dao.txns[1].id, dao.txns[0].transferOf)
        // bKash to own EBL VISA debit card, 3 seconds apart.
        Ingest.sms(dao, "bKash", "bKash to Bank of Tk 2,500.00 for VISA Debit Card is successful. Fee Tk 31.25. Balance Tk 72.73. TrxID AAA0000016 at 29/03/2024 21:39", 10 * min)
        Ingest.sms(dao, "EBL", "AC 123***456 is credited with BDT 2500 as VISA MONEY TRANSFER on 29-MAR-24 09:39:31 PM Balance is BDT 3500.00 Thanks. EBL Helpline 16230", 10 * min + 3_000L)
        assertEquals(dao.txns.last().id, dao.txns.first { it.amount == -250_000L }.transferOf)
        // The same NPSB/Deposit shapes 20 minutes apart stay separate.
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 700 as NPSB FUND TRANSFER on 20-MAR-26 10:00:00 AM Balance is BDT 300.00 Thanks. EBL Helpline 16230", 100 * min)
        Ingest.sms(dao, "CITYBANK", "20-Mar-2026\nTk. 700 Deposit\nTk. 1,000 Balance\nA/C: 1234***5678", 120 * min)
        assertNull(dao.txns.first { it.amount == -70_000L }.transferOf)
    }

    @Test
    fun `an EBL transfer to a friend does not pair with an unrelated wallet receipt`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 500 as EBL Account Transfer on 01-JAN-26 10:00:00 AM Balance is BDT 1000.00 Thanks. EBL Helpline 16230", 1 * min)
        Ingest.sms(dao, "bKash", "You have received Tk 500.00 from 01700000000. Fee Tk 0.00. Balance Tk 745.28. TrxID AAA0000015 at 01/01/2026 10:01", 2 * min)
        assertNull(dao.txns[0].transferOf)
    }

    @Test
    fun `notices are dropped without becoming unparsed`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "bKash", "Payment of Tk 88.00 is being reserved for SOME MERCHANT-RM0000. Balance Tk 186.62. TrxID AAA0000013 at 24/02/2026 20:29", min)
        assertEquals(0, dao.txns.size)
        assertEquals(0, dao.unparsed.size)
    }

    @Test
    fun `a balance set by hand holds until a newer SMS states one`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "NAGAD", "Add Money from Bank is Successful. From: Eastern Bank PLC. Amount: Tk 3200.0 TxnID: 75AAAAAA Balance: Tk 3213.09 20/07/2026 14:46", 1 * min)
        val nagad = dao.accounts.single()
        dao.setBalance(nagad.id, 0L, 10 * min)
        // Re-reading an older SMS (e.g. the inbox scan) must not bring the stale balance back.
        Ingest.sms(dao, "NAGAD", "Money Received. Amount: Tk 11.00 Sender: 01700000000 Ref: x TxnID: 71CCCCCC Balance: Tk 3224.09 21/07/2026 10:00", 5 * min)
        assertEquals(0L, dao.accounts.single().balance)
        Ingest.sms(dao, "NAGAD", "Money Received. Amount: Tk 500.00 Sender: 01700000000 Ref: x TxnID: 71DDDDDD Balance: Tk 500.00 01/10/2026 10:00", 20 * min)
        assertEquals(50_000L, dao.accounts.single().balance)
    }

    @Test
    fun `manual entries go to Cash by default and move only balances older than them`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "NAGAD", "Add Money from Bank is Successful. From: Eastern Bank PLC. Amount: Tk 3200.0 TxnID: 75AAAAAA Balance: Tk 3213.09 20/07/2026 14:46", 10 * min)
        val nagad = dao.accounts.single()
        // Back-dated expense before the balance SMS: the balance stays as stated.
        Ingest.addManual(dao, nagad.id, -100_00L, "Old", 5 * min)
        assertEquals(321_309L, dao.accounts.single().balance)
        // Expense after it: the balance drops.
        Ingest.addManual(dao, nagad.id, -3_213_09L, "Nagad app payment", 20 * min)
        assertEquals(0L, dao.accounts.single { it.id == nagad.id }.balance)
        // No account given: a Cash wallet appears; its balance stays unknown.
        Ingest.addManual(dao, null, -50_00L, "Tea", 30 * min)
        val cash = dao.accounts.single { it.provider == Ingest.CASH }
        assertEquals(cash.id, dao.txns.last().accountId)
        assertEquals("MANUAL", dao.txns.last().source)
        assertNull(cash.balance)
        assertEquals(100_00L + 3_213_09L + 50_00L, dao.spent())
    }

    @Test
    fun `transactions are categorized from the merchant, user rules first`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "bKash", "Payment of Tk 342.80 to FOODPANDA BANGLADESH LIMITED is successful. Balance Tk 1,017.17. TrxID AAA0000020 at 15/07/2023 13:24", 1 * min)
        Ingest.sms(dao, "bKash", "Send Money Tk 500.00 to 01800000000 successful. Ref 1. Fee Tk 5.00. Balance Tk 745.28. TrxID AAA0000021 at 03/02/2026 17:18", 2 * min)
        Ingest.sms(dao, "EBL", "Purchase txn USD3.99 from NETFLIX.COM SINGAP.Card 4520170001 on 04-Oct-26 03:52:08 AM BST.Your A/C 1230456 Balance BDT 43417.94. EBL Helpline 16230", 3 * min)
        Ingest.sms(dao, "EBL", "QR txn BDT 20 through EBL Skybanking at BANGLA QR PAYMENT  from Card 452017**0001 on 01-Oct-26 01:42:16 PM BST. EBL Helpline 16230", 4 * min)
        val byMerchant = dao.txns.associate { it.merchant to it.categoryId }
        assertEquals(dao.categoryId("Food & Dining"), byMerchant["FOODPANDA BANGLADESH"])
        assertEquals(dao.categoryId("Fees & Charges"), byMerchant["bKash fee"])
        assertEquals(dao.categoryId("Subscriptions"), byMerchant["NETFLIX.COM SINGAP"])
        assertNull(byMerchant["BANGLA QR PAYMENT"])
        // "Always use Groceries for BANGLA QR PAYMENT": a user rule, applied to later SMS too.
        dao.insert(CategoryRule(pattern = "=bangla qr payment", categoryId = dao.categoryId("Groceries"), user = true))
        Ingest.sms(dao, "EBL", "QR txn BDT 90 through EBL Skybanking at BANGLA QR PAYMENT  from Card 452017**0001 on 01-Oct-26 01:40:16 PM BST. EBL Helpline 16230", 5 * min)
        assertEquals(dao.categoryId("Groceries"), dao.txns.last().categoryId)
        // An exact rule for "bKash" leaves "bKash fee" in Fees.
        Ingest.sms(dao, "EBL", "AC 123***456 is debited with BDT 700 as EBL Skybanking MFS Transfer-bKash on 01-OCT-26 03:48:17 PM Balance is BDT 44099.59 Thanks. EBL Helpline 16230", 5 * min + 1)
        assertEquals(dao.categoryId("Sent to others"), dao.txns.last().categoryId)
        assertEquals(dao.categoryId("Fees & Charges"), dao.txns.first { it.merchant == "bKash fee" }.categoryId)
        // Manual entries pick a category from their description too.
        Ingest.addManual(dao, null, -120_00L, "Pathao ride", 6 * min)
        assertEquals(dao.categoryId("Transport"), dao.txns.last().categoryId)
    }

    @Test
    fun `bKash fee is a separate expense`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "bKash", "Send Money Tk 500.00 to 01800000000 successful. Ref 1. Fee Tk 5.00. Balance Tk 745.28. TrxID AAA0000005 at 03/02/2026 17:18", min)
        assertEquals(listOf(-50_000L, -500L), dao.txns.map { it.amount })
    }
}

internal class FakeDao : MoneyDao {
    val accounts = mutableListOf<Account>()
    val txns = mutableListOf<Txn>()
    val unparsed = mutableListOf<UnparsedSms>()

    fun spent() = txns.filter { it.amount < 0 && it.transferOf == null && (it.currency == "BDT" || it.bdtAmount != null) }
        .sumOf { -(it.bdtAmount ?: it.amount) }

    override suspend fun accounts(provider: String) = accounts.filter { it.provider == provider }
    override suspend fun account(id: Long) = accounts.first { it.id == id }
    override fun visibleAccounts(): Flow<List<Account>> = flowOf(accounts.filter { it.mergedInto == null })
    override suspend fun insert(account: Account): Long {
        val id = accounts.size + 1L
        accounts += account.copy(id = id)
        return id
    }
    override suspend fun setBalance(id: Long, balance: Long, at: Long) {
        accounts.replaceAll { if (it.id == id) it.copy(balance = balance, balanceAt = at) else it }
    }
    override suspend fun updateBalance(id: Long, balance: Long, at: Long) {
        accounts.replaceAll { if (it.id == id && it.balanceAt <= at) it.copy(balance = balance, balanceAt = at) else it }
    }
    override suspend fun moveTxns(from: Long, into: Long) {
        txns.replaceAll { if (it.accountId == from) it.copy(accountId = into) else it }
    }
    override suspend fun setMergedInto(from: Long, into: Long) {
        accounts.replaceAll { if (it.id == from || it.mergedInto == from) it.copy(mergedInto = into) else it }
    }
    override suspend fun transferCandidates(amount: Long, currency: String, accountId: Long, from: Long, to: Long) =
        txns.filter {
            it.amount == amount && it.currency == currency && it.accountId != accountId &&
                it.transferOf == null && !it.hash.endsWith(":fee") && it.timestamp in from..to
        }.map { TxnSide(it.id, it.amount, it.merchant, it.timestamp, account(it.accountId).provider) }
    override suspend fun reversalCandidates(accountId: Long, amount: Long, currency: String, from: Long, to: Long) =
        txns.filter {
            it.accountId == accountId && it.amount == amount && it.currency == currency &&
                it.transferOf == null && !it.hash.endsWith(":fee") && it.timestamp in from..to
        }.sortedByDescending { it.timestamp }
            .map { TxnSide(it.id, it.amount, it.merchant, it.timestamp, account(it.accountId).provider) }
    override suspend fun linkTransfer(a: Long, b: Long) {
        txns.replaceAll { when (it.id) { a -> it.copy(transferOf = b); b -> it.copy(transferOf = a); else -> it } }
    }
    override suspend fun insert(txn: Txn): Long {
        if (txns.any { it.hash == txn.hash }) return -1
        val id = txns.size + 1L
        txns += txn.copy(id = id)
        return id
    }
    override suspend fun insert(sms: UnparsedSms): Long { unparsed += sms; return unparsed.size.toLong() }
    override suspend fun unparsed(): List<UnparsedSms> = unparsed.toList()
    override suspend fun deleteUnparsed(id: Long) { unparsed.removeAll { it.id == id } }
    override fun txns(from: Long, to: Long): Flow<List<TxnRow>> = flowOf(emptyList())
    override fun spent(from: Long, to: Long): Flow<Long> = flowOf(spent())
    override fun unparsedCount(): Flow<Int> = flowOf(unparsed.size)
    val categories = DEFAULT_CATEGORIES.mapIndexed { i, (name, emoji, _) -> Category(i + 1L, name, emoji) }.toMutableList()
    val rules = DEFAULT_CATEGORIES.flatMapIndexed { i, (_, _, ps) -> ps.map { CategoryRule(0, it, i + 1L, false) } }.toMutableList()
    fun categoryId(name: String) = categories.first { it.name == name }.id
    override fun received(from: Long, to: Long): Flow<Long> = flowOf(0)
    override fun spentByCategory(from: Long, to: Long): Flow<List<CategoryTotal>> = flowOf(emptyList())
    override fun categories(): Flow<List<Category>> = flowOf(categories)
    override suspend fun insert(category: Category): Long { val id = categories.size + 1L; categories += category.copy(id = id); return id }
    override suspend fun updateCategory(id: Long, name: String, emoji: String) { categories.replaceAll { if (it.id == id) it.copy(name = name, emoji = emoji) else it } }
    override suspend fun deleteCategoryRow(id: Long) { categories.removeAll { it.id == id } }
    override suspend fun uncategorize(id: Long) { txns.replaceAll { if (it.categoryId == id) it.copy(categoryId = null) else it } }
    override suspend fun deleteRulesFor(id: Long) { rules.removeAll { it.categoryId == id } }
    override suspend fun rules() = rules.sortedWith(compareByDescending<CategoryRule> { it.user }.thenByDescending { it.pattern.length })
    override suspend fun insert(rule: CategoryRule): Long { rules.removeAll { it.pattern == rule.pattern }; rules += rule; return 1 }
    override suspend fun setCategory(id: Long, categoryId: Long?) { txns.replaceAll { if (it.id == id) it.copy(categoryId = categoryId) else it } }
    override suspend fun setCategoryForMerchant(merchant: String, categoryId: Long) {
        txns.replaceAll { if (it.merchant.equals(merchant, ignoreCase = true)) it.copy(categoryId = categoryId) else it }
    }
    override suspend fun renameManual(id: Long, merchant: String?) {
        txns.replaceAll { if (it.id == id && it.source == "MANUAL") it.copy(merchant = merchant) else it }
    }
    override fun transferred(from: Long, to: Long): Flow<Long> = flowOf(0)
    override suspend fun markTransfer(id: Long) { linkTransfer(id, id) }
    override suspend fun adjustBalance(id: Long, delta: Long, at: Long) {
        accounts.replaceAll { if (it.id == id && it.balance != null && it.balanceAt <= at) it.copy(balance = it.balance + delta, balanceAt = at) else it }
    }
    override suspend fun unlinkFrom(id: Long) { txns.replaceAll { if (it.transferOf == id) it.copy(transferOf = null) else it } }
    override suspend fun deleteTxn(id: Long) { txns.removeAll { it.id == id } }
    override suspend fun deleteSmsTxns() { txns.removeAll { it.source != "MANUAL" } }
    override suspend fun clearLinks() { txns.replaceAll { it.copy(transferOf = null) } }
    override suspend fun deleteUnparsedAll() { unparsed.clear() }
    override suspend fun deleteUnusedAccounts() { accounts.removeAll { a -> txns.none { it.accountId == a.id } } }
    override suspend fun resetAccounts() { accounts.replaceAll { it.copy(balance = null, balanceAt = 0, mergedInto = null) } }
    override suspend fun unlink(a: Long, b: Long) {
        txns.replaceAll { if (it.id == a || it.id == b) it.copy(transferOf = null) else it }
    }
}
