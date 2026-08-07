package io.github.yulbax.frkn.data.profile

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val link: String,
    val outboundJson: String,
    val selected: Boolean = false,
    val subscriptionUrl: String = ""
)
