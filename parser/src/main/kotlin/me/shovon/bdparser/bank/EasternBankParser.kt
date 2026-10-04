/*
 * Derived from Cashiro (https://github.com/ritesh-kanwar/Cashiro),
 * Copyright (C) Ritesh Kanwar, licensed under the GNU AGPL v3.
 *
 * Modified 2026-09-01: extracted into the standalone bd-sms-parsers library and
 * repackaged as me.shovon.bdparser. See NOTICE for the full list of changes.
 *
 * This file is part of bd-sms-parsers, licensed under the GNU AGPL v3.
 * See LICENSE for the full text.
 */
package me.shovon.bdparser.bank

import me.shovon.bdparser.TransactionType
import me.shovon.bdparser.bank.BankParser.Companion.BalanceUpdateInfo
import java.math.BigDecimal
import me.shovon.bdparser.SimpleDate

/**
 * Parser for Eastern Bank Limited (EBL, Bangladesh) SMS messages.
 *
 * EBL uses two distinct alert shapes: an account-level "AC <masked> is {credited|debited} with
 * BDT X as <reason> on <date> <time> [BST]" form, and a card-level "EBL CARDS: ..." form.
 *
 * Supported formats:
 * - "AC <masked> is {credited|debited} with BDT X as <reason> on <date> <time> [BST] [Balance is BDT Y]"
 *   for any reason (NPSB FUND TRANSFER, EBL Account Transfer, EBL Skybanking MFS Transfer-bKash,
 *   IC INTEREST LIQUIDATION, WITHOLDING SOURCE TAX ON CASA ACCOUNTS, ...)
 * - "[EBL CARDS: ]NPSB Fund Transfer BDT X using Card <masked> on <date> <time> [BST]"
 * - "[EBL CARDS: ]Payment of BDT X credited to Card <masked> on <date> <time> [BST]. Balance: BDT Y."
 * - "[EBL CARDS: ]Purchase txn BDT X from <merchant>. Card <masked> on <date> <time> [BST]. Balance: BDT Y."
 * - "Purchase txn BDT X from <merchant> <terminal>.Card <n> on <date> ... Your A/C <n> Balance BDT Y." (debit card)
 * - "Purchase txn USD3.99 from <merchant>.Card <n> on <date> ... Your A/C <n> Balance BDT Y." (foreign
 *   purchase: the amount is in USD, the balance stays in the account's BDT)
 * - "QR txn BDT X through EBL Skybanking at <merchant> from Card <masked> on <date> ..."
 *
 * The trailing "Balance: BDT Y" is parsed into [me.shovon.bdparser.ParsedTransaction.balance]
 * on BOTH the payment-credited and purchase shapes: real-world reconciliation (credit limit minus
 * the post-payment balance equalling the true outstanding amount after a known payment) confirms
 * that Y reports the card's remaining AVAILABLE CREDIT on both shapes, not an outstanding-owed
 * figure - see [creditCardBalanceIsAvailableCredit] and the comment on [match]'s
 * cardsPaymentCreditedPattern branch.
 *
 * The monthly-bill/statement notice ("Monthly bill <masked card> <MONYYYY>; Total Due: BDT
 * <total>, Min Due: BDT <min>, Last Pmt: <dd-MON-yy>. Statement link <url>") is NOT a
 * transaction - none of the [match] patterns above recognise it, so it stays rejected by
 * [parse]/[isTransactionMessage] - but is recognised by [isBalanceUpdateNotification]/
 * [parseBalanceUpdate] as a credit-card statement anchor. [BalanceUpdateInfo.totalDue] is
 * surfaced as both [BalanceUpdateInfo.totalDue] and [BalanceUpdateInfo.balance] (the CC
 * outstanding balance). The trailing date is labelled "Last Pmt" but, read in context with
 * "Total Due"/"Min Due" on the same statement notice, is EBL's wording for the payment due
 * date rather than a historical last-payment date, so it is mapped to
 * [BalanceUpdateInfo.dueDate]. EBL's statement format does not expose a credit limit.
 *
 * Common senders: EBL, Eastern Bank
 * Currency: BDT (Bangladeshi Taka)
 */
class EasternBankParser : BangladeshBankParser() {

    override fun getBankName() = "Eastern Bank"

    /**
     * Both EBL card-balance alert shapes - "EBL CARDS: Purchase txn ... Balance: BDT Y" (falls as
     * purchases are made) and "EBL CARDS: Payment of BDT X credited to Card ... Balance: BDT Y"
     * (rises as payments are made) - report the card's remaining AVAILABLE CREDIT, not an
     * outstanding-owed figure. See [BangladeshBankParser.creditCardBalanceIsAvailableCredit] for
     * how this differs from the base class's safe default.
     */
    override fun creditCardBalanceIsAvailableCredit(): Boolean = true

    override fun canHandle(sender: String): Boolean {
        val upper = sender.uppercase().trim()
        return upper == "EBL" ||
                upper.contains("EASTERNBANK") ||
                upper.contains("EASTERN BANK") ||
                upper.matches(Regex("""^[A-Z]{2}-EBL-[A-Z]$"""))
    }

    /**
     * Body-marker fallback for when Mobile Number Portability has rewritten the sender ID away
     * from a recognisable Eastern Bank sender: matches when the body mentions "Skybanking"
     * (EBL's digital banking channel), or "EBL" as a whole word (covers "EBL Account Transfer",
     * "EBL CARDS", "EBL Skybanking ..."). "EBL" alone is short and generic, so it is matched
     * with a word-boundary regex to avoid false positives inside unrelated words.
     */
    override fun matchesBodyMarkers(message: String): Boolean {
        if (message.contains("skybanking", ignoreCase = true)) return true
        return eblWordPattern.containsMatchIn(message)
    }

    /**
     * The account-level "AC <masked> is credited with BDT X as NPSB FUND TRANSFER" alert carries
     * no "EBL" token, so under MNP it is only reachable via the format patterns themselves. The
     * monthly-bill statement notice is included too, since it is dispatched through the same
     * [canHandleMessage] gate before [isBalanceUpdateNotification] ever runs.
     */
    override fun matchesKnownFormat(message: String): Boolean =
        match(message) != null || statementPattern.containsMatchIn(message)

    /** Compiled once; [canHandleMessage] runs for every parser against every unmatched SMS. */
    private val eblWordPattern = Regex("""\bebl\b""", RegexOption.IGNORE_CASE)

    private val takaFigure = """([0-9][0-9,]*(?:\.\d{1,2})?)"""

    // "AC <masked> is {credited|debited} with BDT X as <reason> on <date> ... [Balance is BDT Y]"
    // One pattern for every reason (transfers, interest, tax, ...); the reason becomes the merchant.
    private val accountPattern = Regex(
        """AC\s+[0-9*]+\s+is (credited|debited) with BDT\s*$takaFigure\s+as\s+(.+?)\s+on\s+\d""",
        RegexOption.IGNORE_CASE
    )
    private val accountBalanceSuffix = Regex("""Balance is BDT\s*$takaFigure""", RegexOption.IGNORE_CASE)

    // The card shapes below come with or without the "EBL CARDS:" prefix.
    private val cardsPrefix = """(?:EBL CARDS:\s*)?"""

    // "NPSB Fund Transfer BDT X using Card <masked> on ..."
    private val cardsNpsbPattern = Regex(
        """${cardsPrefix}NPSB Fund Transfer\s+BDT\s*$takaFigure\s+using Card\s+[0-9*]+""",
        RegexOption.IGNORE_CASE
    )

    // "Payment of BDT X credited to Card <masked> on ... Balance: BDT Y."
    private val cardsPaymentCreditedPattern = Regex(
        """${cardsPrefix}Payment\s+of\s+BDT\s*$takaFigure\s+credited to Card\s+[0-9*]+""",
        RegexOption.IGNORE_CASE
    )

    // Purchases name their currency: "Purchase txn USD3.99 from NETFLIX.COM SINGAP.Card ...".
    // "Purchase txn BDT X from <merchant>. Card <masked> on ... Balance: BDT Y."            (credit card)
    // "Purchase txn BDT X from <merchant> <terminal>.Card <n> on ... Your A/C <n> Balance BDT Y." (debit card)
    private val cardsPurchasePattern = Regex(
        """${cardsPrefix}Purchase txn\s+([A-Z]{3})\s*$takaFigure\s+from\s+(.+?)\.\s*Card\s+[0-9*]+""",
        RegexOption.IGNORE_CASE
    )

    // "QR txn BDT X through EBL Skybanking at <merchant> from Card <masked> on ..."
    private val cardsQrPattern = Regex(
        """QR txn\s+BDT\s*$takaFigure\s+through\s+.+?\s+at\s+(.+?)\s+from Card\s+[0-9*]+""",
        RegexOption.IGNORE_CASE
    )

    private val cardsBalanceSuffix = Regex(
        """Balance:\s*BDT\s*$takaFigure""",
        RegexOption.IGNORE_CASE
    )

    // Debit-card purchases report the linked account's balance, not available credit.
    private val linkedAccountBalanceSuffix = Regex(
        """A/C\s+[0-9*]+\s+Balance\s+BDT\s*$takaFigure""",
        RegexOption.IGNORE_CASE
    )

    // Purchase merchants carry a trailing terminal id: "btcl.gov.bd 024831".
    private val trailingTerminalId = Regex("""\s+\d+$""")

    private data class Match(
        val amount: String,
        val type: TransactionType,
        val merchant: String?,
        val balance: String?,
        val currency: String = "BDT"
    )

    private fun match(message: String): Match? {
        accountPattern.find(message)?.let {
            val type = if (it.groupValues[1].equals("credited", ignoreCase = true)) {
                TransactionType.INCOME
            } else {
                TransactionType.EXPENSE
            }
            val reason = it.groupValues[3].trim()
            val merchant = when {
                reason.equals("NPSB FUND TRANSFER", ignoreCase = true) -> "NPSB Fund Transfer"
                reason.contains("bKash", ignoreCase = true) -> "bKash"
                else -> reason
            }
            val balance = accountBalanceSuffix.find(message)?.groupValues?.get(1)
            return Match(it.groupValues[2], type, merchant, balance)
        }
        cardsNpsbPattern.find(message)?.let {
            return Match(it.groupValues[1], TransactionType.EXPENSE, "NPSB Fund Transfer", null)
        }
        cardsPaymentCreditedPattern.find(message)?.let {
            // The trailing "Balance: BDT Y" on this payment-credited shape reports the card's
            // remaining AVAILABLE CREDIT, same semantic as the purchase shape below - confirmed
            // by reconciling credit limit minus this balance against a known payment amount.
            // Unlike EBL, MutualTrustBankParser's equivalent payment patterns still map to
            // BalanceKind.NONE since MTB's payment-balance semantic is unconfirmed.
            return Match(it.groupValues[1], TransactionType.INCOME, null, cardBalance(message))
        }
        cardsPurchasePattern.find(message)?.let {
            val merchant = it.groupValues[3].trim().replace(trailingTerminalId, "")
            return Match(it.groupValues[2], TransactionType.EXPENSE, merchant, cardBalance(message),
                it.groupValues[1].uppercase())
        }
        cardsQrPattern.find(message)?.let {
            return Match(it.groupValues[1], TransactionType.EXPENSE, it.groupValues[2].trim(), null)
        }
        return null
    }

    private fun cardBalance(message: String): String? =
        (linkedAccountBalanceSuffix.find(message) ?: cardsBalanceSuffix.find(message))?.groupValues?.get(1)

    override fun isTransactionMessage(message: String): Boolean {
        if (match(message) != null) return true
        return super.isTransactionMessage(message)
    }

    override fun extractAmount(message: String): BigDecimal? =
        match(message)?.let { parseTakaAmount(it.amount) }

    override fun extractBalance(message: String): BigDecimal? =
        match(message)?.balance?.let { parseTakaAmount(it) }

    override fun extractTransactionType(message: String): TransactionType? = match(message)?.type

    override fun extractCurrency(message: String): String? = match(message)?.currency

    override fun extractMerchant(message: String, sender: String): String? = match(message)?.merchant

    // "Monthly bill 532900******0000 JAN2020; Total Due: BDT 10000.00, Min Due: BDT 1000.00,
    //  Last Pmt: 01-JAN-20. Statement link https://example.com/statement"
    private val statementPattern = Regex(
        """Monthly bill\s+([0-9Xx*]+)\s+([A-Za-z]{3,9})(\d{4});\s*Total Due:\s*BDT\s*$takaFigure,\s*Min Due:\s*BDT\s*$takaFigure,\s*Last Pmt:\s*(\d{1,2})-([A-Za-z]{3,9})-(\d{2,4})""",
        RegexOption.IGNORE_CASE
    )

    override fun isBalanceUpdateNotification(message: String): Boolean {
        return statementPattern.containsMatchIn(message)
    }

    /**
     * Parses EBL's credit-card monthly bill/statement notice. See class doc for why the
     * "Last Pmt" date is mapped to [BalanceUpdateInfo.dueDate]. No credit limit is exposed by
     * this format.
     */
    override fun parseBalanceUpdate(message: String): BalanceUpdateInfo? {
        val match = statementPattern.find(message) ?: return null

        val cardLast4 = extractLast4Digits(match.groupValues[1]) ?: return null
        val statementMonth = parseMonthAbbreviation(match.groupValues[2]) ?: return null
        val statementYear = match.groupValues[3].toIntOrNull() ?: return null
        val totalDue = parseTakaAmount(match.groupValues[4]) ?: return null
        val minDue = parseTakaAmount(match.groupValues[5])
        val dueDay = match.groupValues[6].toIntOrNull()
        val dueMonth = parseMonthAbbreviation(match.groupValues[7])
        val dueYear = resolveYear(match.groupValues[8])

        val statementDate = SimpleDate.ofOrNull(statementYear, statementMonth, 1)
        val dueDate = if (dueDay != null && dueMonth != null && dueYear != null) {
            SimpleDate.ofOrNull(dueYear, dueMonth, dueDay)
        } else null

        return BalanceUpdateInfo(
            bankName = getBankName(),
            accountLast4 = cardLast4,
            balance = totalDue,
            isCreditCard = true,
            totalDue = totalDue,
            minDue = minDue,
            dueDate = dueDate,
            statementDate = statementDate
        )
    }
}
