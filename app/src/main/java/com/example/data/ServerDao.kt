package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY id ASC")
    fun getAllServersFlow(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY id ASC")
    suspend fun getAllServers(): List<ServerEntity>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getServerById(id: Long): ServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: ServerEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(servers: List<ServerEntity>): List<Long>

    @Update
    suspend fun updateServer(server: ServerEntity)

    @Delete
    suspend fun deleteServer(server: ServerEntity)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE servers SET pingMs = :pingMs, lastTestedTime = :testedTime WHERE id = :id")
    suspend fun updatePing(id: Long, pingMs: Long, testedTime: Long)

    @Query("UPDATE servers SET isAutoSelected = :isAutoSelected WHERE id = :id")
    suspend fun updateAutoSelected(id: Long, isAutoSelected: Boolean)

    @Query("UPDATE servers SET isAutoSelected = 0")
    suspend fun clearAutoSelected()

    @Query("DELETE FROM servers WHERE isCustom = 0")
    suspend fun deleteNonCustom()

    @Query("DELETE FROM servers")
    suspend fun deleteAll()
}
