package com.fahimscirex.alpha.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Money is stored in minor units (paisa, cents) as Long so sums are exact.
 * [number] is whatever account/card digits the SMS shows ("982", "0001"); empty for wallets.
 */
@Entity(indices = [Index("provider")])
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val provider: String,
    val number: String,
    val currency: String = "BDT",
    val balance: Long? = null,
    val balanceAt: Long = 0,
    /**
     * Set when this row is another view of [mergedInto] (e.g. a debit card of a bank account).
     * The row is kept so later SMS naming this number still resolve to the target account.
     */
    val mergedInto: Long? = null,
)

@Entity(indices = [Index(value = ["hash"], unique = true), Index("timestamp")])
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Dedup key: the same SMS seen by the receiver and the inbox scan is stored once. */
    val hash: String,
    val accountId: Long,
    /** Signed: negative for money out. */
    val amount: Long,
    val currency: String = "BDT",
    val merchant: String?,
    val timestamp: Long,
    val source: String,
    val note: String? = null,
    /**
     * Id of the other half when this is one side of a transfer between own accounts (or the
     * purchase a reversal undoes). Its own id when the user marked it a transfer by hand.
     */
    val transferOf: Long? = null,
    /** Signed taka value of a foreign-currency transaction, when known; null for BDT ones. */
    val bdtAmount: Long? = null,
    val categoryId: Long? = null,
    /** The user picked [categoryId] (not a rule); a re-import that keeps edits restores it. */
    @ColumnInfo(defaultValue = "0") val userCategory: Boolean = false,
    /** The user set [transferOf] (marked or unlinked); a re-import that keeps edits restores it. */
    @ColumnInfo(defaultValue = "0") val userLink: Boolean = false,
)

/** A user's change to one transaction, keyed by its stable [hash] so it survives a rebuild. */
data class UserEdit(
    val hash: String,
    val categoryId: Long?,
    val userCategory: Boolean,
    val userLink: Boolean,
    /** Hash of the linked transaction; equal to [hash] when marked a transfer by hand. */
    val partnerHash: String?,
)

/** A category; [emoji] is its icon, so users can pick any without assets. */
@Entity
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
)

/**
 * Assigns [categoryId] to transactions whose merchant contains [pattern] (lowercase). Built-in
 * rules ship with the app; [user] rules come from "always use this category" and win.
 */
@Entity(indices = [Index(value = ["pattern"], unique = true)])
data class CategoryRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pattern: String,
    val categoryId: Long,
    val user: Boolean,
)

data class CategoryTotal(val categoryId: Long?, val name: String?, val emoji: String?, val total: Long)

/** SMS from a known sender that looked financial but failed to parse; shown for parser fixes. */
@Entity(indices = [Index(value = ["sender", "body", "timestamp"], unique = true)])
data class UnparsedSms(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val timestamp: Long,
)

data class TxnRow(
    val id: Long,
    val amount: Long,
    val currency: String,
    val merchant: String?,
    val timestamp: Long,
    val provider: String,
    val number: String,
    val transferOf: Long?,
    val bdtAmount: Long?,
    /** "SMS" or "MANUAL". */
    val source: String,
    val categoryId: Long?,
    val emoji: String?,
    val categoryName: String?,
    /** The linked other half is on the same account: a reversal, not a transfer. */
    val reversed: Boolean,
)

/** A transfer candidate: an unlinked transaction plus its account's provider. */
data class TxnSide(val id: Long, val amount: Long, val merchant: String?, val timestamp: Long, val provider: String)

@Dao
interface MoneyDao {
    @Query("SELECT * FROM Account WHERE provider = :provider")
    suspend fun accounts(provider: String): List<Account>

    @Query("SELECT * FROM Account WHERE id = :id")
    suspend fun account(id: Long): Account

    /** Accounts as shown to the user: merged rows are folded into their target. */
    @Query("SELECT * FROM Account WHERE mergedInto IS NULL ORDER BY provider, number")
    fun visibleAccounts(): Flow<List<Account>>

    @Query("UPDATE Txn SET accountId = :into WHERE accountId = :from")
    suspend fun moveTxns(from: Long, into: Long)

    @Query("UPDATE Account SET mergedInto = :into WHERE id = :from OR mergedInto = :from")
    suspend fun setMergedInto(from: Long, into: Long)

    @Query(
        """SELECT t.id, t.amount, t.merchant, t.timestamp, a.provider FROM Txn t JOIN Account a ON a.id = t.accountId
           WHERE t.amount = :amount AND t.currency = :currency AND t.accountId != :accountId
             AND t.transferOf IS NULL AND t.hash NOT LIKE '%:fee' AND t.timestamp BETWEEN :from AND :to"""
    )
    suspend fun transferCandidates(amount: Long, currency: String, accountId: Long, from: Long, to: Long): List<TxnSide>

    /** Unlinked earlier movements on the same account, newest first. */
    @Query(
        """SELECT t.id, t.amount, t.merchant, t.timestamp, a.provider FROM Txn t JOIN Account a ON a.id = t.accountId
           WHERE t.accountId = :accountId AND t.amount = :amount AND t.currency = :currency
             AND t.transferOf IS NULL AND t.hash NOT LIKE '%:fee' AND t.timestamp BETWEEN :from AND :to
           ORDER BY t.timestamp DESC"""
    )
    suspend fun reversalCandidates(accountId: Long, amount: Long, currency: String, from: Long, to: Long): List<TxnSide>

    @Query("UPDATE Txn SET transferOf = CASE id WHEN :a THEN :b ELSE :a END WHERE id IN (:a, :b)")
    suspend fun linkTransfer(a: Long, b: Long)

    @Insert
    suspend fun insert(account: Account): Long

    /** Balance stated by the user; an SMS newer than [at] replaces it again. */
    @Query("UPDATE Account SET balance = :balance, balanceAt = :at WHERE id = :id")
    suspend fun setBalance(id: Long, balance: Long, at: Long)

    /** Moves a known balance by [delta] for an entry dated at or after it; unknown balances stay unknown. */
    @Query("UPDATE Account SET balance = balance + :delta, balanceAt = :at WHERE id = :id AND balance IS NOT NULL AND balanceAt <= :at")
    suspend fun adjustBalance(id: Long, delta: Long, at: Long)

    @Query("UPDATE Account SET balance = :balance, balanceAt = :at WHERE id = :id AND balanceAt <= :at")
    suspend fun updateBalance(id: Long, balance: Long, at: Long)

    /** Returns -1 when the hash already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: Txn): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sms: UnparsedSms): Long

    @Query(
        """SELECT t.id, t.amount, t.currency, t.merchant, t.timestamp, a.provider, a.number, t.transferOf, t.bdtAmount, t.source,
                  t.categoryId, c.emoji, c.name AS categoryName,
                  COALESCE((SELECT o.accountId FROM Txn o WHERE o.id = t.transferOf AND o.id != t.id) = t.accountId, 0) AS reversed
           FROM Txn t JOIN Account a ON a.id = t.accountId LEFT JOIN Category c ON c.id = t.categoryId
           WHERE t.timestamp >= :from AND t.timestamp < :to ORDER BY t.timestamp DESC"""
    )
    fun txns(from: Long, to: Long): Flow<List<TxnRow>>

    /** Taka spent: BDT transactions plus foreign ones whose taka value is known. */
    @Query(
        """SELECT COALESCE(SUM(-COALESCE(bdtAmount, amount)), 0) FROM Txn
           WHERE amount < 0 AND transferOf IS NULL AND (currency = 'BDT' OR bdtAmount IS NOT NULL)
             AND timestamp >= :from AND timestamp < :to"""
    )
    fun spent(from: Long, to: Long): Flow<Long>

    /** Money received, excluding transfers between own accounts and reversals. */
    @Query(
        """SELECT COALESCE(SUM(COALESCE(bdtAmount, amount)), 0) FROM Txn
           WHERE amount > 0 AND transferOf IS NULL AND (currency = 'BDT' OR bdtAmount IS NOT NULL)
             AND timestamp >= :from AND timestamp < :to"""
    )
    fun received(from: Long, to: Long): Flow<Long>

    /** Spending per category, largest first; uncategorized spending has a null category. */
    @Query(
        """SELECT t.categoryId, c.name, c.emoji, SUM(-COALESCE(t.bdtAmount, t.amount)) AS total
           FROM Txn t LEFT JOIN Category c ON c.id = t.categoryId
           WHERE t.amount < 0 AND t.transferOf IS NULL AND (t.currency = 'BDT' OR t.bdtAmount IS NOT NULL)
             AND t.timestamp >= :from AND t.timestamp < :to
           GROUP BY t.categoryId ORDER BY total DESC"""
    )
    fun spentByCategory(from: Long, to: Long): Flow<List<CategoryTotal>>

    @Query("SELECT * FROM Category ORDER BY name")
    fun categories(): Flow<List<Category>>

    @Insert
    suspend fun insert(category: Category): Long

    @Query("UPDATE Category SET name = :name, emoji = :emoji WHERE id = :id")
    suspend fun updateCategory(id: Long, name: String, emoji: String)

    @Query("DELETE FROM Category WHERE id = :id")
    suspend fun deleteCategoryRow(id: Long)

    @Query("UPDATE Txn SET categoryId = NULL WHERE categoryId = :id")
    suspend fun uncategorize(id: Long)

    @Query("DELETE FROM CategoryRule WHERE categoryId = :id")
    suspend fun deleteRulesFor(id: Long)

    /** User rules first, then longer (more specific) patterns. */
    @Query("SELECT * FROM CategoryRule ORDER BY user DESC, LENGTH(pattern) DESC")
    suspend fun rules(): List<CategoryRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: CategoryRule): Long

    /** The user's category choice. */
    @Query("UPDATE Txn SET categoryId = :categoryId, userCategory = 1 WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: Long?)

    /** Re-categorizes every transaction from [merchant], for "always use this category". */
    @Query("UPDATE Txn SET categoryId = :categoryId WHERE LOWER(merchant) = LOWER(:merchant)")
    suspend fun setCategoryForMerchant(merchant: String, categoryId: Long)

    @Query("UPDATE Txn SET merchant = :merchant WHERE id = :id AND source = 'MANUAL'")
    suspend fun renameManual(id: Long, merchant: String?)

    /** Money moved out to another own account: linked transfers, not reversed purchases. */
    @Query(
        """SELECT COALESCE(SUM(-COALESCE(bdtAmount, amount)), 0) FROM Txn t
           WHERE amount < 0 AND transferOf IS NOT NULL AND (currency = 'BDT' OR bdtAmount IS NOT NULL)
             AND (transferOf = id OR (SELECT o.accountId FROM Txn o WHERE o.id = t.transferOf) != accountId)
             AND timestamp >= :from AND timestamp < :to"""
    )
    fun transferred(from: Long, to: Long): Flow<Long>

    /** Marks a transaction as a transfer between own accounts when no counterpart SMS exists. */
    @Query("UPDATE Txn SET transferOf = id, userLink = 1 WHERE id = :id")
    suspend fun markTransfer(id: Long)

    @Query("UPDATE Txn SET transferOf = NULL, userLink = 1 WHERE id IN (:a, :b)")
    suspend fun unlink(a: Long, b: Long)

    /** Deletes a transaction, unlinking whatever was paired with it. */
    @Query("UPDATE Txn SET transferOf = NULL WHERE transferOf = :id")
    suspend fun unlinkFrom(id: Long)

    @Query("DELETE FROM Txn WHERE id = :id")
    suspend fun deleteTxn(id: Long)

    // Re-import: drop everything derived from SMS but keep transactions entered by hand.
    @Query("DELETE FROM Txn WHERE source != 'MANUAL'")
    suspend fun deleteSmsTxns()

    @Query("UPDATE Txn SET transferOf = NULL")
    suspend fun clearLinks()

    @Query("DELETE FROM UnparsedSms")
    suspend fun deleteUnparsedAll()

    @Query("DELETE FROM Account WHERE id NOT IN (SELECT accountId FROM Txn)")
    suspend fun deleteUnusedAccounts()

    @Query("UPDATE Account SET balance = NULL, balanceAt = 0, mergedInto = NULL")
    suspend fun resetAccounts()

    @Query("UPDATE Txn SET userCategory = 0, userLink = 0")
    suspend fun forgetUserEdits()

    // Re-import that keeps the user's changes (see Ingest.rebuild).
    @Query(
        """SELECT hash, categoryId, userCategory, userLink, (SELECT o.hash FROM Txn o WHERE o.id = t.transferOf) AS partnerHash
           FROM Txn t WHERE userCategory = 1 OR userLink = 1"""
    )
    suspend fun userEdits(): List<UserEdit>

    @Query("SELECT * FROM Account")
    suspend fun allAccounts(): List<Account>

    /** Clears balances for a replay, keeping merges. */
    @Query("UPDATE Account SET balance = NULL, balanceAt = 0")
    suspend fun resetBalances()

    @Query("UPDATE Txn SET transferOf = NULL WHERE userLink = 0")
    suspend fun clearAutoLinks()

    @Query("SELECT * FROM Txn WHERE hash = :hash")
    suspend fun byHash(hash: String): Txn?

    @Query("UPDATE Txn SET transferOf = :transferOf, userLink = :user WHERE id = :id")
    suspend fun setLink(id: Long, transferOf: Long?, user: Boolean)

    @Query("UPDATE Txn SET categoryId = :categoryId, userCategory = 1 WHERE hash = :hash")
    suspend fun restoreCategory(hash: String, categoryId: Long?)

    @Query("SELECT * FROM UnparsedSms")
    suspend fun unparsed(): List<UnparsedSms>

    @Query("DELETE FROM UnparsedSms WHERE id = :id")
    suspend fun deleteUnparsed(id: Long)

    @Query("SELECT COUNT(*) FROM UnparsedSms")
    fun unparsedCount(): Flow<Int>
}

@Database(
    entities = [Account::class, Txn::class, UnparsedSms::class, Category::class, CategoryRule::class],
    version = 5, exportSchema = false,
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): MoneyDao

    companion object {
        @Volatile private var instance: AppDb? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Account ADD COLUMN mergedInto INTEGER")
                db.execSQL("ALTER TABLE Txn ADD COLUMN transferOf INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Txn ADD COLUMN bdtAmount INTEGER")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Txn ADD COLUMN categoryId INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS `Category` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `emoji` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `CategoryRule` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `pattern` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `user` INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_CategoryRule_pattern` ON `CategoryRule` (`pattern`)")
                seedCategories(db)
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Txn ADD COLUMN userCategory INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE Txn ADD COLUMN userLink INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "alpha.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) = seedCategories(db)
                })
                .build().also { instance = it }
        }
    }
}
