package pl.ggretro.android.data

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
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import pl.ggretro.protocol.GgStatus

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val uin: Int,
    val name: String,
    val status: Int = GgStatus.NOT_AVAIL,
    val description: String? = null,
    val imageSizeKb: Int = 0,
    val unread: Int = 0,
    val lastMessageAt: Long = 0,
)

val ContactEntity.displayName: String get() = name.ifBlank { uin.toString() }

object Ack {
    const val NONE = 0 // incoming message
    const val PENDING = -1
    const val FAILED = -2 // not connected when sending
}

@Entity(tableName = "messages", indices = [Index("contactUin"), Index(value = ["contactUin", "seq"])])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contactUin: Int,
    val outgoing: Boolean,
    /** Unix time in milliseconds. */
    val time: Long,
    /** Display text ('\n' line breaks). */
    val text: String,
    /** Serialized formats, see [FormatCodec]. */
    val formats: String = "",
    val seq: Int = 0,
    /** [Ack] constant or a GG ack status (AckStatus.*). */
    val ack: Int = Ack.NONE,
)

object ImageState {
    const val PENDING = 0
    const val READY = 1
    const val UNAVAILABLE = 2
}

@Entity(tableName = "images")
data class ImageEntity(
    @PrimaryKey val imageKey: String,
    val ownerUin: Int,
    val state: Int,
    val requestedAt: Long = 0,
)

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY CASE WHEN status IN (2,4,3,5) THEN 0 ELSE 1 END, name COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts")
    suspend fun all(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE uin = :uin")
    fun observe(uin: Int): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE uin = :uin")
    suspend fun get(uin: Int): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(contact: ContactEntity): Long

    @Upsert
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET name = :name WHERE uin = :uin")
    suspend fun rename(uin: Int, name: String)

    @Query("UPDATE contacts SET status = :status, description = :description, imageSizeKb = :imageSizeKb WHERE uin = :uin")
    suspend fun updateStatus(uin: Int, status: Int, description: String?, imageSizeKb: Int)

    @Query("UPDATE contacts SET status = 1, description = NULL, imageSizeKb = 0")
    suspend fun markAllOffline()

    @Query("UPDATE contacts SET unread = unread + 1, lastMessageAt = :time WHERE uin = :uin")
    suspend fun incrementUnread(uin: Int, time: Long)

    @Query("UPDATE contacts SET unread = 0 WHERE uin = :uin")
    suspend fun clearUnread(uin: Int)

    @Query("UPDATE contacts SET lastMessageAt = :time WHERE uin = :uin")
    suspend fun touch(uin: Int, time: Long)

    @Query("DELETE FROM contacts WHERE uin = :uin")
    suspend fun delete(uin: Int)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE contactUin = :uin ORDER BY time, id")
    fun observeConversation(uin: Int): Flow<List<MessageEntity>>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET ack = :ack WHERE contactUin = :uin AND seq = :seq AND outgoing = 1")
    suspend fun updateAck(uin: Int, seq: Int, ack: Int)

    @Query("UPDATE messages SET ack = :ack WHERE id = :id")
    suspend fun updateAckById(id: Long, ack: Int)

    @Query("DELETE FROM messages WHERE contactUin = :uin")
    suspend fun deleteConversation(uin: Int)
}

@Dao
interface ImageDao {
    @Query("SELECT * FROM images")
    fun observeAll(): Flow<List<ImageEntity>>

    @Query("SELECT * FROM images WHERE imageKey = :key")
    suspend fun get(key: String): ImageEntity?

    @Upsert
    suspend fun upsert(image: ImageEntity)
}

@Database(entities = [ContactEntity::class, MessageEntity::class, ImageEntity::class], version = 1, exportSchema = true)
abstract class GgDatabase : RoomDatabase() {
    abstract fun contacts(): ContactDao
    abstract fun messages(): MessageDao
    abstract fun images(): ImageDao

    companion object {
        fun create(context: Context): GgDatabase =
            Room.databaseBuilder(context, GgDatabase::class.java, "gg.db").build()
    }
}
