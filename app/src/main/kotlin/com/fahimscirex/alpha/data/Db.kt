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
)

@Dao
interface MoneyDao {
    @Query("SELECT * FROM Account WHERE provider = :provider")
    suspend fun accounts(provider: String): List<Account>

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
        """SELECT t.id, t.amount, t.currency, t.merchant, t.timestamp, a.provider, a.number
           FROM Txn t JOIN Account a ON a.id = t.accountId
           WHERE t.timestamp >= :from AND t.timestamp < :to ORDER BY t.timestamp DESC"""
    )
    fun txns(from: Long, to: Long): Flow<List<TxnRow>>

    @Query("SELECT COALESCE(SUM(-amount), 0) FROM Txn WHERE amount < 0 AND currency = 'BDT' AND timestamp >= :from AND timestamp < :to")
    fun spent(from: Long, to: Long): Flow<Long>

    @Query("SELECT COUNT(*) FROM UnparsedSms")
    fun unparsedCount(): Flow<Int>
}

@Database(entities = [Account::class, Txn::class, UnparsedSms::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): MoneyDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "alpha.db")
                .build().also { instance = it }
        }
    }
}
