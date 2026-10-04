package com.fahimscirex.alpha.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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
    /** Id of the other half when this is one side of a transfer between own accounts. */
    val transferOf: Long? = null,
    /** Signed taka value of a foreign-currency transaction, when known; null for BDT ones. */
    val bdtAmount: Long? = null,
)

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

    @Query("UPDATE Txn SET transferOf = CASE id WHEN :a THEN :b ELSE :a END WHERE id IN (:a, :b)")
    suspend fun linkTransfer(a: Long, b: Long)

    @Insert
    suspend fun insert(account: Account): Long

    @Query("UPDATE Account SET balance = :balance, balanceAt = :at WHERE id = :id AND balanceAt <= :at")
    suspend fun updateBalance(id: Long, balance: Long, at: Long)

    /** Returns -1 when the hash already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: Txn): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sms: UnparsedSms): Long

    @Query(
        """SELECT t.id, t.amount, t.currency, t.merchant, t.timestamp, a.provider, a.number, t.transferOf, t.bdtAmount
           FROM Txn t JOIN Account a ON a.id = t.accountId
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

    @Query("SELECT * FROM UnparsedSms")
    suspend fun unparsed(): List<UnparsedSms>

    @Query("DELETE FROM UnparsedSms WHERE id = :id")
    suspend fun deleteUnparsed(id: Long)

    @Query("SELECT COUNT(*) FROM UnparsedSms")
    fun unparsedCount(): Flow<Int>
}

@Database(entities = [Account::class, Txn::class, UnparsedSms::class], version = 3, exportSchema = false)
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

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "alpha.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
