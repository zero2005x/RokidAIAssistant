package com.example.rokidphone.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Recording Source - where the recording was captured
 */
enum class RecordingSource {
    PHONE,      // Recorded from phone microphone
    GLASSES     // Recorded from glasses microphone
}

/**
 * Recording Status - processing state of the recording
 */
enum class RecordingStatus {
    RECORDING,      // Currently recording
    COMPLETED,      // Recording completed, ready for processing
    TRANSCRIBING,   // Speech-to-text in progress
    TRANSCRIBED,    // Transcription completed
    ANALYZING,      // AI analysis in progress
    ANALYZED,       // AI analysis completed
    ERROR           // Error occurred during processing
}

/**
 * Recording Entity
 * Stores audio recordings with their transcription and AI response
 */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey
    val id: String,
    
    @ColumnInfo(name = "title")
    val title: String,
    
    @ColumnInfo(name = "file_path")
    val filePath: String,
    
    @ColumnInfo(name = "source")
    val source: RecordingSource = RecordingSource.PHONE,
    
    @ColumnInfo(name = "status")
    val status: RecordingStatus = RecordingStatus.COMPLETED,
    
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0,
    
    @ColumnInfo(name = "file_size_bytes")
    val fileSizeBytes: Long = 0,
    
    @ColumnInfo(name = "sample_rate")
    val sampleRate: Int = 16000,
    
    @ColumnInfo(name = "channels")
    val channels: Int = 1,
    
    @ColumnInfo(name = "transcript")
    val transcript: String? = null,
    
    @ColumnInfo(name = "ai_response")
    val aiResponse: String? = null,
    
    @ColumnInfo(name = "provider_id")
    val providerId: String? = null,
    
    @ColumnInfo(name = "model_id")
    val modelId: String? = null,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "transcribed_at")
    val transcribedAt: Long? = null,
    
    @ColumnInfo(name = "analyzed_at")
    val analyzedAt: Long? = null,
    
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    
    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,
    
    @ColumnInfo(name = "notes")
    val notes: String? = null
)

/**
 * Recording DAO
 */
@Dao
interface RecordingDao {
    
    @Query("SELECT * FROM recordings ORDER BY created_at DESC")
    fun getAllRecordings(): Flow<List<RecordingEntity>>
    
    @Query("SELECT * FROM recordings WHERE is_favorite = 1 ORDER BY created_at DESC")
    fun getFavoriteRecordings(): Flow<List<RecordingEntity>>
    
    @Query("SELECT * FROM recordings WHERE source = :source ORDER BY created_at DESC")
    fun getRecordingsBySource(source: RecordingSource): Flow<List<RecordingEntity>>
    
    @Query("SELECT * FROM recordings WHERE status = :status ORDER BY created_at DESC")
    fun getRecordingsByStatus(status: RecordingStatus): Flow<List<RecordingEntity>>
    
    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: String): RecordingEntity?
    
    @Query("SELECT * FROM recordings WHERE id = :id")
    fun getRecordingByIdFlow(id: String): Flow<RecordingEntity?>
    
    @Query("SELECT * FROM recordings WHERE title LIKE '%' || :query || '%' OR transcript LIKE '%' || :query || '%' ORDER BY created_at DESC")
    fun searchRecordings(query: String): Flow<List<RecordingEntity>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: RecordingEntity)
    
    @Update
    suspend fun update(recording: RecordingEntity)
    
    @Delete
    suspend fun delete(recording: RecordingEntity)
    
    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: String)
    
    @Query("DELETE FROM recordings WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
    
    @Query("UPDATE recordings SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE recordings SET notes = :notes, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateNotes(id: String, notes: String?, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE recordings SET is_favorite = :isFavorite, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean, updatedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE recordings SET transcript = :transcript, status = :status, transcribed_at = :transcribedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTranscript(
        id: String,
        transcript: String,
        status: RecordingStatus = RecordingStatus.TRANSCRIBED,
        transcribedAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    )
    
    @Query("UPDATE recordings SET ai_response = :aiResponse, provider_id = :providerId, model_id = :modelId, status = :status, analyzed_at = :analyzedAt, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateAiResponse(
        id: String,
        aiResponse: String,
        providerId: String?,
        modelId: String?,
        status: RecordingStatus = RecordingStatus.ANALYZED,
        analyzedAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis()
    )
    
    @Query("UPDATE recordings SET status = :status, error_message = :errorMessage, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateError(
        id: String,
        status: RecordingStatus = RecordingStatus.ERROR,
        errorMessage: String?,
        updatedAt: Long = System.currentTimeMillis()
    )
    
    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun getRecordingCount(): Int
    
    @Query("SELECT SUM(duration_ms) FROM recordings")
    suspend fun getTotalDurationMs(): Long?
}

/**
 * Type Converters for Recording enums
 */
class RecordingConverters {
    @TypeConverter
    fun fromRecordingSource(source: RecordingSource): String = source.name
    
    @TypeConverter
    fun toRecordingSource(value: String): RecordingSource = 
        RecordingSource.valueOf(value)
    
    @TypeConverter
    fun fromRecordingStatus(status: RecordingStatus): String = status.name
    
    @TypeConverter
    fun toRecordingStatus(value: String): RecordingStatus = 
        RecordingStatus.valueOf(value)
}
