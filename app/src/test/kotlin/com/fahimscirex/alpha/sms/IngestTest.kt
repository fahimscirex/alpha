package com.fahimscirex.alpha.sms

import com.fahimscirex.alpha.data.Account
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
    fun `bKash fee is a separate expense`() = runBlocking {
        val dao = FakeDao()
        Ingest.sms(dao, "bKash", "Send Money Tk 500.00 to 01800000000 successful. Ref 1. Fee Tk 5.00. Balance Tk 745.28. TrxID AAA0000005 at 03/02/2026 17:18", min)
        assertEquals(listOf(-50_000L, -500L), dao.txns.map { it.amount })
    }
}

private class FakeDao : MoneyDao {
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
}
