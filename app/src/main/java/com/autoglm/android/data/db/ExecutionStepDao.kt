package com.autoglm.android.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExecutionStepDao {
    @Query("SELECT * FROM execution_steps WHERE messageId = :messageId ORDER BY stepNumber ASC")
    fun getStepsByMessageId(messageId: String): Flow<List<ExecutionStep>>

    @Query("SELECT * FROM execution_steps WHERE messageId = :messageId ORDER BY stepNumber ASC")
    suspend fun getStepsByMessageIdSync(messageId: String): List<ExecutionStep>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: ExecutionStep)

    @Delete
    suspend fun deleteStep(step: ExecutionStep)

    @Query("DELETE FROM execution_steps WHERE messageId = :messageId")
    suspend fun deleteStepsByMessageId(messageId: String)

    @Query("SELECT * FROM execution_steps WHERE messageId = :messageId ORDER BY stepNumber DESC LIMIT 1")
    suspend fun getLastStep(messageId: String): ExecutionStep?
}
