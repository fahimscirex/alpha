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
        if (parser.isBalanceUpdateNotification(body) || parser.isNotice(body)) return@withLock

        val t = parser.parse(body, sender, timestamp)
        if (t == null) {
            // Only from the provider's own sender: a body-only match ("pay through bKash" in an
            // ISP bill reminder) that fails to parse is just someone else's SMS.
            if (parser.canHandle(sender) && SmsFilter.isTransactionMessage(body)) {
                dao.insert(UnparsedSms(sender = sender, body = body, timestamp = timestamp))
            }
            return@withLock
        }

        // "Purchase txn BDT 0 / USD0" card verification checks move no money.
        if (t.amount.signum() == 0) return@withLock

        val sign = when (t.type) {
            TransactionType.INCOME -> 1
            TransactionType.EXPENSE, TransactionType.CREDIT -> -1
            // Not produced by the BD parsers; recorded for review rather than guessed.
            else -> {
                dao.insert(UnparsedSms(sender = sender, body = body, timestamp = timestamp))
                return@withLock
            }
        }
        // The account is in the bank's own currency even when a purchase is in USD.
        val account = account(dao, t.bankName, t.accountLast4.orEmpty(), parser.getCurrency())
        t.cardLast4?.let { linkCard(dao, t.bankName, it, account) }
        val hash = t.generateTransactionId()
        val amount = sign * minor(t.amount)
        val txn = Txn(hash = hash, accountId = account.id, amount = amount, currency = t.currency,
            merchant = t.merchant, timestamp = timestamp, source = "SMS",
            bdtAmount = if (t.currency != account.currency) takaCost(account, t.balance, timestamp, sign) else null)
        val id = dao.insert(txn)
        if (id > 0) {
            if (t.isReversal) linkReversal(dao, txn.copy(id = id)) else linkTransfer(dao, txn.copy(id = id), account.provider)
        }
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
     * Taka cost of a foreign-currency transaction: the drop in the account balance since the
     * last known one, which includes the bank's FX markup. Only trusted when that balance is
     * older than this SMS and moved in the transaction's direction; an SMS missed in between
     * would still skew it (known limit).
     */
    private fun takaCost(account: Account, balance: BigDecimal?, timestamp: Long, sign: Int): Long? {
        val before = account.balance ?: return null
        if (balance == null || account.balanceAt >= timestamp) return null
        return (minor(balance) - before).takeIf { it != 0L && (it > 0) == (sign > 0) }
    }

    /** Runs [block] while no SMS is being ingested, e.g. to wipe the database. */
    suspend fun exclusive(block: suspend () -> Unit) = lock.withLock { block() }

    /** Merges [from] into [into]: its transactions move over and its number resolves to [into] from now on. */
    suspend fun merge(dao: MoneyDao, from: Account, into: Account) = lock.withLock { mergeLocked(dao, from, into) }

    private suspend fun mergeLocked(dao: MoneyDao, from: Account, into: Account) {
        if (from.id == into.id) return
        dao.moveTxns(from.id, into.id)
        dao.setMergedInto(from.id, into.id)
    }

    /** Resolves the provider's account for [number], following merges; creates it if new. */
    private suspend fun account(dao: MoneyDao, provider: String, number: String, currency: String): Account {
        val existing = findRow(dao, provider, number, currency)
            ?: return Account(provider = provider, number = number, currency = currency).let { it.copy(id = dao.insert(it)) }
        return existing.mergedInto?.let { dao.account(it) } ?: existing
    }

    /**
     * Finds the provider's account row by number suffix, since one bank shows the same account
     * as "123***456" in one SMS and "1230456" in another (parsed as "456" and "0456").
     * An exact match wins over a suffix match.
     */
    private suspend fun findRow(dao: MoneyDao, provider: String, number: String, currency: String): Account? {
        val rows = dao.accounts(provider).filter { it.currency == currency }
        return rows.firstOrNull { it.number == number } ?: rows.firstOrNull {
            number.length >= 3 && it.number.length >= 3 && (it.number.endsWith(number) || number.endsWith(it.number))
        }
    }

    /** An SMS naming both a card and its account ties the card to that account for good. */
    private suspend fun linkCard(dao: MoneyDao, provider: String, card: String, account: Account) {
        val row = findRow(dao, provider, card, account.currency)
        when {
            row == null -> dao.insert(Account(provider = provider, number = card, currency = account.currency, mergedInto = account.id))
            row.mergedInto == null -> mergeLocked(dao, row, account)
        }
    }

    /**
     * Links [txn] to the opposite movement of the same amount on another own account within
     * [TRANSFER_WINDOW_MS], when each side names the other's provider, e.g. EBL "debited ...
     * as EBL Skybanking MFS Transfer-bKash" and bKash "received deposit ... from Eastern Bank PLC".
     * Both must name each other: an EBL transfer to a friend's bKash plus an unrelated bKash
     * receipt of the same amount must not pair up.
     */
    private suspend fun linkTransfer(dao: MoneyDao, txn: Txn, provider: String) {
        val match = dao.transferCandidates(-txn.amount, txn.currency, txn.accountId,
            txn.timestamp - TRANSFER_WINDOW_MS, txn.timestamp + TRANSFER_WINDOW_MS)
            .filter {
                counterparts(txn.merchant, provider, it.merchant, it.provider) ||
                    // Neither side names the other (EBL "NPSB Fund Transfer" -> City "Deposit"):
                    // only both being plain transfer movements within a few minutes will do.
                    (isGenericMove(txn.merchant) && isGenericMove(it.merchant) &&
                        kotlin.math.abs(it.timestamp - txn.timestamp) <= UNNAMED_WINDOW_MS)
            }
            .minByOrNull { kotlin.math.abs(it.timestamp - txn.timestamp) } ?: return
        dao.linkTransfer(txn.id, match.id)
    }

    /**
     * At least one side must name the other's provider; the other side must name it back or
     * at least be a generic card or transfer movement. So EBL "Purchase txn ... from BKASH
     * LIMITED" pairs with bKash "deposit ... from VISA Card", and an older EBL "EBL Account
     * Transfer" pairs with bKash "deposit from iBanking ... from Eastern Bank Limited", while
     * an unrelated bKash receipt (names nothing) never pairs.
     */
    internal fun counterparts(aMerchant: String?, aProvider: String, bMerchant: String?, bProvider: String): Boolean {
        val aNamesB = names(aMerchant, bProvider)
        val bNamesA = names(bMerchant, aProvider)
        return (aNamesB || bNamesA) && (aNamesB || isGenericMove(aMerchant)) && (bNamesA || isGenericMove(bMerchant))
    }

    /** Short names other providers' SMS use for a provider, e.g. Tap's "Received ... from EBL". */
    private val aliases = mapOf("Eastern Bank" to listOf("EBL"), "Rocket (DBBL)" to listOf("Rocket", "DBBL"))

    /** "Eastern Bank PLC" names "Eastern Bank"; "from EBL" names it too. Whole words only. */
    private fun names(merchant: String?, provider: String): Boolean {
        if (merchant == null) return false
        return (listOf(provider) + aliases[provider].orEmpty()).any {
            Regex("""\b${Regex.escape(it)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(merchant)
        }
    }

    /** Labels of plain money movements, as opposed to purchases and merchant payments. */
    private val genericMoveWords = listOf("card", "transfer", "npsb", "deposit")

    private fun isGenericMove(merchant: String?) =
        merchant != null && genericMoveWords.any { merchant.contains(it, ignoreCase = true) }

    /**
     * Pairs a card reversal with the purchase it undoes (same account, currency and amount,
     * within [REVERSAL_WINDOW_MS] before it), preferring one whose merchant starts the same.
     * The pair then cancels out of spending. Without a match the reversal stays as income.
     */
    private suspend fun linkReversal(dao: MoneyDao, txn: Txn) {
        val prefix = txn.merchant.orEmpty().take(6)
        val candidates = dao.reversalCandidates(txn.accountId, -txn.amount, txn.currency, txn.timestamp - REVERSAL_WINDOW_MS, txn.timestamp)
        val match = candidates.firstOrNull { prefix.isNotEmpty() && it.merchant.orEmpty().startsWith(prefix, ignoreCase = true) }
            ?: candidates.firstOrNull() ?: return
        dao.linkTransfer(txn.id, match.id)
    }

    private const val REVERSAL_WINDOW_MS = 60 * 86_400_000L

    private const val TRANSFER_WINDOW_MS = 15 * 60_000L
    private const val UNNAMED_WINDOW_MS = 5 * 60_000L

    // Shortcut: assumes 2 decimal places, true for BDT and USD; revisit for JPY-like currencies.
    private fun minor(amount: BigDecimal): Long = amount.setScale(2, RoundingMode.HALF_UP).unscaledValue().toLong()
}
