package voice.core.data.repo.internals.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import voice.core.data.InstalledVoice

@Dao
public interface InstalledVoiceDao {

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  public suspend fun insert(voice: InstalledVoice)

  @Query("SELECT * FROM installedVoice")
  public suspend fun all(): List<InstalledVoice>

  @Query("SELECT * FROM installedVoice WHERE voiceId = :voiceId")
  public suspend fun get(voiceId: String): InstalledVoice?

  @Query("DELETE FROM installedVoice WHERE voiceId = :voiceId")
  public suspend fun delete(voiceId: String)
}
