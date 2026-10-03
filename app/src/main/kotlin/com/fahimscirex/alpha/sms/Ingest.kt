package com.fahimscirex.alpha.sms

import com.fahimscirex.alpha.data.Account
import com.fahimscirex.alpha.data.MoneyDao
import com.fahimscirex.alpha.data.Txn
import com.fahimscirex.alpha.data.UnparsedSms
import me.shovon.bdparser.SmsFilter
import me.shovon.bdparser.TransactionType
import me.shovon.bdparser.bank.BankParserFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode

/** The single entry point both the SMS receiver and the inbox scan feed. */
object Ingest {

    // The receiver and the inbox scan can run at once; serialise so an account is created once.
    private val lock = Mutex()

    suspend fun sms(dao: MoneyDao, sender: String, body: String, timestamp: Long) = lock.withLock {
        val parser = BankParserFactory.getParser(sender, body) ?: return@withLock
        // Card statements and balance-only notices: handled once cards exist (v2).
        if (parser.isBalanceUpdateNotification(body)) return@withLock

        val t = parser.parse(body, sender, timestamp)
        if (t == null) {
            if (SmsFilter.isTransactionMessage(body)) dao.insert(UnparsedSms(sender = sender, body = body, timestamp = timestamp))
            return@withLock
        }

        val sign = when (t.type) {
            TransactionType.INCOME -> 1
            TransactionType.EXPENSE, TransactionType.CREDIT -> -1
            // Not produced by the BD parsers; recorded for review rather than guessed.
            else -> {
                dao.insert(UnparsedSms(sender = sender, body = body, timestamp = timestamp))
                return@withLock
            }
        }
        val account = account(dao, t.bankName, t.accountLast4.orEmpty(), t.currency)
        val hash = t.generateTransactionId()
        dao.insert(Txn(hash = hash, accountId = account.id, amount = sign * minor(t.amount), currency = t.currency,
            merchant = t.merchant, timestamp = timestamp, source = "SMS"))
        t.fee?.let {
            dao.insert(Txn(hash = "$hash:fee", accountId = account.id, amount = -minor(it), currency = t.currency,
                merchant = "${t.bankName} fee", timestamp = timestamp, source = "SMS"))
        }
        // A credit card's "balance" is its available credit, not money held; skip until cards exist (v2).
        if (!(t.isFromCard && t.creditCardBalanceIsAvailableCredit)) {
            t.balance?.let { dao.updateBalance(account.id, minor(it), timestamp) }
        }
    }

    /**
     * Finds the provider's account by number suffix, since one bank shows the same account as
     * "134***982" in one SMS and "1343982" in another (parsed as "982" and "3982").
     */
    private suspend fun account(dao: MoneyDao, provider: String, number: String, currency: String): Account {
        val existing = dao.accounts(provider).firstOrNull {
            it.currency == currency && (it.number == number ||
                (number.length >= 3 && it.number.length >= 3 && (it.number.endsWith(number) || number.endsWith(it.number))))
        }
        if (existing != null) return existing
        val created = Account(provider = provider, number = number, currency = currency)
        return created.copy(id = dao.insert(created))
    }

    // Shortcut: assumes 2 decimal places, true for BDT and USD; revisit for JPY-like currencies.
    private fun minor(amount: BigDecimal): Long = amount.setScale(2, RoundingMode.HALF_UP).unscaledValue().toLong()
}
